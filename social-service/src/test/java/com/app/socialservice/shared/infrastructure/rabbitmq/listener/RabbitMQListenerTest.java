package com.app.socialservice.shared.infrastructure.rabbitmq.listener;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import com.app.socialservice.block.application.service.BlockNodeService;
import com.app.socialservice.block.infrastructure.events.UserBlockedEvent;
import com.app.socialservice.shared.infrastructure.entity.ProcessedEvent;
import com.app.socialservice.shared.infrastructure.rabbitmq.config.RabbitMQProperties;
import com.app.socialservice.shared.infrastructure.repository.ProcessedEventsRepository;
import com.app.socialservice.user.application.commands.DeleteUserCommand;
import com.app.socialservice.user.application.commands.UpdateAuthUserInfoCommand;
import com.app.socialservice.user.application.commands.UserRegisterCommand;
import com.app.socialservice.user.application.service.UserNodeService;
import com.app.socialservice.user.application.service.UserService;
import com.app.socialservice.user.infrastructure.events.UserDeletedFromAuthEvent;
import com.app.socialservice.user.infrastructure.events.UserInfoFromAuthUpdatedEvent;
import com.app.socialservice.user.infrastructure.events.UserRegisteredFromAuthEvent;
import com.app.socialservice.user.infrastructure.mapper.UserRegisterCommandMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RabbitMQListenerTest {

    @Mock
    private UserService userService;

    @Mock
    private UserRegisterCommandMapper userRegisterCommandMapper;

    @Mock
    private UserNodeService userNodeService;

    @Mock
    private BlockNodeService blockNodeService;

    @Mock
    private ProcessedEventsRepository processedEventsRepository;

    @Spy
    private RabbitMQProperties rabbitMQProperties = new RabbitMQProperties();

    @InjectMocks
    private RabbitMQListener rabbitMQListener;

    @BeforeEach
    void setUp() {
        rabbitMQProperties.getQueue().getAuth().setRegister("q.social-service.auth.register");
        rabbitMQProperties.getQueue().getAuth().setUpdate("q.social-service.auth.update");
        rabbitMQProperties.getQueue().getAuth().setDelete("q.social-service.auth.delete");
        rabbitMQProperties.getQueue().getUser().setRegister("q.social-service.user.register");
        rabbitMQProperties.getQueue().getUser().setDeleted("q.social-service.user.deleted");
        rabbitMQProperties.getQueue().getUser().getBlock().setCreated("q.social-service.user.block.created");
    }

    @Test
    void shouldValidateInputFieldsForUserAuthEventsInRabbitMqListener() {
        var invalidRegisterEvent = new UserRegisteredFromAuthEvent(
                1L,
                "REGISTER",
                "realm",
                "client",
                "not-a-uuid",
                "session",
                "127.0.0.1",
                null,
                new UserRegisteredFromAuthEvent.Details(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        "user@example.com",
                        "username"
                )
        );
        var invalidUpdateEvent = new UserInfoFromAuthUpdatedEvent(
                2L,
                "UPDATE",
                "realm",
                "client",
                UUID.randomUUID().toString(),
                "session",
                "127.0.0.1",
                null,
                null
        );
        var invalidDeleteEvent = new UserDeletedFromAuthEvent(
                3L,
                "",
                "realm",
                "client",
                UUID.randomUUID().toString(),
                "session",
                "127.0.0.1",
                null,
                null
        );

        assertThatThrownBy(() -> rabbitMQListener.onUserRegisteredFromAuth(invalidRegisterEvent))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("event.userId must be a valid UUID");

        assertThatThrownBy(() -> rabbitMQListener.onUserInfoFromAuthUpdated(invalidUpdateEvent))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("event.details must not be null");

        assertThatThrownBy(() -> rabbitMQListener.onUserDeletedFromAuth(invalidDeleteEvent))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("event.type must not be blank");
    }

    @Test
    void shouldDiscardMessageIfAlreadyProcessedInRabbitMqListener() {
        var event = userRegisteredFromAuthEvent();
        var expectedEventId = deterministicUuid(
                "auth-register-event",
                event.type(),
                event.userId(),
                String.valueOf(event.time()),
                event.details().username(),
                event.details().email()
        );
        var expectedCorrelationId = deterministicUuid(
                "auth-register-correlation",
                event.type(),
                event.userId(),
                String.valueOf(event.time())
        );

        when(processedEventsRepository.existsById(expectedEventId)).thenReturn(false);
        when(processedEventsRepository.existsByCorrelationId(expectedCorrelationId)).thenReturn(true);

        rabbitMQListener.onUserRegisteredFromAuth(event);

        verify(userRegisterCommandMapper, never()).toCommand(any(), any(), any(), any());
        verify(userService, never()).registerUser(any());
        verify(processedEventsRepository, never()).save(any(ProcessedEvent.class));
    }

    @Test
    void shouldMarkMessageAsProcessedAfterSuccessfulServiceExecutionInRabbitMqListener() {
        var event = userRegisteredFromAuthEvent();
        var expectedEventId = deterministicUuid(
                "auth-register-event",
                event.type(),
                event.userId(),
                String.valueOf(event.time()),
                event.details().username(),
                event.details().email()
        );
        var expectedCorrelationId = deterministicUuid(
                "auth-register-correlation",
                event.type(),
                event.userId(),
                String.valueOf(event.time())
        );
        var command = new UserRegisterCommand(
                expectedEventId,
                expectedCorrelationId,
                UUID.fromString(event.userId()),
                event.details().username(),
                event.details().email(),
                Instant.ofEpochMilli(event.time()),
                UserRegisteredFromAuthEvent.class.getSimpleName()
        );

        when(processedEventsRepository.existsById(expectedEventId)).thenReturn(false);
        when(processedEventsRepository.existsByCorrelationId(expectedCorrelationId)).thenReturn(false);
        when(userRegisterCommandMapper.toCommand(
                expectedEventId,
                expectedCorrelationId,
                event,
                UserRegisteredFromAuthEvent.class.getSimpleName()
        )).thenReturn(command);

        rabbitMQListener.onUserRegisteredFromAuth(event);

        verify(userService).registerUser(command);

        var processedEventCaptor = ArgumentCaptor.forClass(ProcessedEvent.class);
        verify(processedEventsRepository).save(processedEventCaptor.capture());
        assertThat(processedEventCaptor.getValue().getId()).isEqualTo(expectedEventId);
        assertThat(processedEventCaptor.getValue().getCorrelationId()).isEqualTo(expectedCorrelationId);
        assertThat(processedEventCaptor.getValue().getEventType())
                .isEqualTo(UserRegisteredFromAuthEvent.class.getSimpleName());
    }

    @Test
    void shouldMarkUpdateAuthMessageAsProcessedAfterSuccessfulServiceExecutionInRabbitMqListener() {
        var event = userInfoFromAuthUpdatedEvent();
        var expectedEventId = deterministicUuid(
                "auth-update-event",
                event.type(),
                event.userId(),
                String.valueOf(event.time()),
                event.details().username(),
                event.details().email()
        );
        var expectedCorrelationId = deterministicUuid(
                "auth-update-correlation",
                event.type(),
                event.userId(),
                String.valueOf(event.time())
        );

        when(processedEventsRepository.existsById(expectedEventId)).thenReturn(false);
        when(processedEventsRepository.existsByCorrelationId(expectedCorrelationId)).thenReturn(false);

        rabbitMQListener.onUserInfoFromAuthUpdated(event);

        var commandCaptor = ArgumentCaptor.forClass(UpdateAuthUserInfoCommand.class);
        verify(userService).updateUserAuthInfo(commandCaptor.capture());
        assertThat(commandCaptor.getValue().id()).isEqualTo(expectedEventId);
        assertThat(commandCaptor.getValue().correlationId()).isEqualTo(expectedCorrelationId);
        assertThat(commandCaptor.getValue().userId()).isEqualTo(UUID.fromString(event.userId()));
        assertThat(commandCaptor.getValue().username()).isEqualTo(event.details().username());
        assertThat(commandCaptor.getValue().email()).isEqualTo(event.details().email());

        var processedEventCaptor = ArgumentCaptor.forClass(ProcessedEvent.class);
        verify(processedEventsRepository).save(processedEventCaptor.capture());
        assertThat(processedEventCaptor.getValue().getId()).isEqualTo(expectedEventId);
        assertThat(processedEventCaptor.getValue().getCorrelationId()).isEqualTo(expectedCorrelationId);
        assertThat(processedEventCaptor.getValue().getEventType())
                .isEqualTo(UserInfoFromAuthUpdatedEvent.class.getSimpleName());
    }

    @Test
    void shouldDiscardUpdateAuthMessageWhenAlreadyProcessedInRabbitMqListener() {
        var event = userInfoFromAuthUpdatedEvent();
        var expectedEventId = deterministicUuid(
                "auth-update-event",
                event.type(),
                event.userId(),
                String.valueOf(event.time()),
                event.details().username(),
                event.details().email()
        );
        var expectedCorrelationId = deterministicUuid(
                "auth-update-correlation",
                event.type(),
                event.userId(),
                String.valueOf(event.time())
        );

        when(processedEventsRepository.existsById(expectedEventId)).thenReturn(false);
        when(processedEventsRepository.existsByCorrelationId(expectedCorrelationId)).thenReturn(true);

        rabbitMQListener.onUserInfoFromAuthUpdated(event);

        verify(userService, never()).updateUserAuthInfo(any(UpdateAuthUserInfoCommand.class));
        verify(processedEventsRepository, never()).save(any(ProcessedEvent.class));
    }

    @Test
    void shouldMarkDeleteAuthMessageAsProcessedAfterSuccessfulServiceExecutionInRabbitMqListener() {
        var event = userDeletedFromAuthEvent();
        var expectedEventId = deterministicUuid(
                "auth-delete-event",
                event.type(),
                event.userId(),
                String.valueOf(event.time())
        );
        var expectedCorrelationId = deterministicUuid(
                "auth-delete-correlation",
                event.type(),
                event.userId(),
                String.valueOf(event.time())
        );

        when(processedEventsRepository.existsById(expectedEventId)).thenReturn(false);
        when(processedEventsRepository.existsByCorrelationId(expectedCorrelationId)).thenReturn(false);

        rabbitMQListener.onUserDeletedFromAuth(event);

        var commandCaptor = ArgumentCaptor.forClass(DeleteUserCommand.class);
        verify(userService).deleteUser(commandCaptor.capture());
        assertThat(commandCaptor.getValue().id()).isEqualTo(expectedEventId);
        assertThat(commandCaptor.getValue().correlationId()).isEqualTo(expectedCorrelationId);
        assertThat(commandCaptor.getValue().userId()).isEqualTo(UUID.fromString(event.userId()));

        var processedEventCaptor = ArgumentCaptor.forClass(ProcessedEvent.class);
        verify(processedEventsRepository).save(processedEventCaptor.capture());
        assertThat(processedEventCaptor.getValue().getId()).isEqualTo(expectedEventId);
        assertThat(processedEventCaptor.getValue().getCorrelationId()).isEqualTo(expectedCorrelationId);
        assertThat(processedEventCaptor.getValue().getEventType())
                .isEqualTo(UserDeletedFromAuthEvent.class.getSimpleName());
    }

    @Test
    void shouldDiscardDeleteAuthMessageWhenAlreadyProcessedInRabbitMqListener() {
        var event = userDeletedFromAuthEvent();
        var expectedEventId = deterministicUuid(
                "auth-delete-event",
                event.type(),
                event.userId(),
                String.valueOf(event.time())
        );
        var expectedCorrelationId = deterministicUuid(
                "auth-delete-correlation",
                event.type(),
                event.userId(),
                String.valueOf(event.time())
        );

        when(processedEventsRepository.existsById(expectedEventId)).thenReturn(true);

        rabbitMQListener.onUserDeletedFromAuth(event);

        verify(userService, never()).deleteUser(any(DeleteUserCommand.class));
        verify(processedEventsRepository, never()).save(any(ProcessedEvent.class));
        verify(processedEventsRepository, never()).existsByCorrelationId(expectedCorrelationId);
    }

    @Test
    void shouldDelegateUserBlockedEventToBlockNodeServiceAndRecordProcessedEvent() {
        var blockerId = UUID.randomUUID();
        var blockedId = UUID.randomUUID();
        var event = UserBlockedEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(UUID.randomUUID())
                .occurredAt(Instant.now())
                .blockerUserId(blockerId)
                .blockedUserId(blockedId)
                .build();
        when(processedEventsRepository.existsById(event.id())).thenReturn(false);
        when(processedEventsRepository.existsByCorrelationId(event.correlationId())).thenReturn(false);

        rabbitMQListener.onUserBlocked(event);

        verify(blockNodeService).deleteBidirectionalFollowRelationship(blockerId, blockedId);
        var processedEventCaptor = ArgumentCaptor.forClass(ProcessedEvent.class);
        verify(processedEventsRepository).save(processedEventCaptor.capture());
        assertThat(processedEventCaptor.getValue().getId()).isEqualTo(event.id());
        assertThat(processedEventCaptor.getValue().getCorrelationId()).isEqualTo(event.correlationId());
        assertThat(processedEventCaptor.getValue().getEventType()).isEqualTo(UserBlockedEvent.class.getSimpleName());
    }

    @Test
    void shouldSkipBlockNodeCleanupWhenBlockEventIdWasAlreadyProcessed() {
        var event = UserBlockedEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(UUID.randomUUID())
                .occurredAt(Instant.now())
                .blockerUserId(UUID.randomUUID())
                .blockedUserId(UUID.randomUUID())
                .build();
        when(processedEventsRepository.existsById(event.id())).thenReturn(true);

        rabbitMQListener.onUserBlocked(event);

        verify(blockNodeService, never()).deleteBidirectionalFollowRelationship(any(), any());
        verify(processedEventsRepository, never()).save(any(ProcessedEvent.class));
    }

    @Test
    void shouldRethrowWhenBlockNodeCleanupFails() {
        var event = UserBlockedEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(UUID.randomUUID())
                .occurredAt(Instant.now())
                .blockerUserId(UUID.randomUUID())
                .blockedUserId(UUID.randomUUID())
                .build();
        when(processedEventsRepository.existsById(event.id())).thenReturn(false);
        when(processedEventsRepository.existsByCorrelationId(event.correlationId())).thenReturn(false);

        doThrow(new RuntimeException("neo4j cleanup failed"))
                .when(blockNodeService)
                .deleteBidirectionalFollowRelationship(event.blockerUserId(), event.blockedUserId());

        assertThatThrownBy(() -> rabbitMQListener.onUserBlocked(event))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("neo4j cleanup failed");

        verify(processedEventsRepository, never()).save(any(ProcessedEvent.class));
    }

    private UserRegisteredFromAuthEvent userRegisteredFromAuthEvent() {
        return new UserRegisteredFromAuthEvent(
                1_717_171_717_000L,
                "REGISTER",
                "social",
                "auth-service",
                UUID.randomUUID().toString(),
                "session",
                "127.0.0.1",
                null,
                new UserRegisteredFromAuthEvent.Details(
                        "pwd",
                        "code",
                        "self",
                        "http://localhost",
                        "Test",
                        "User",
                        "registered@example.com",
                        "registered-user"
                )
        );
    }

    private UserInfoFromAuthUpdatedEvent userInfoFromAuthUpdatedEvent() {
        return new UserInfoFromAuthUpdatedEvent(
                1_717_171_818_000L,
                "UPDATE",
                "social",
                "auth-service",
                UUID.randomUUID().toString(),
                "session",
                "127.0.0.1",
                null,
                new UserInfoFromAuthUpdatedEvent.Details(
                        "pwd",
                        "code",
                        "self",
                        "http://localhost",
                        "Updated",
                        "User",
                        "updated@example.com",
                        "updated-user"
                )
        );
    }

    private UserDeletedFromAuthEvent userDeletedFromAuthEvent() {
        return new UserDeletedFromAuthEvent(
                1_717_171_919_000L,
                "DELETE",
                "social",
                "auth-service",
                UUID.randomUUID().toString(),
                "session",
                "127.0.0.1",
                null,
                new UserDeletedFromAuthEvent.Details(
                        "pwd",
                        "code",
                        "self",
                        "http://localhost",
                        "Deleted",
                        "User",
                        "deleted@example.com",
                        "deleted-user"
                )
        );
    }

    private UUID deterministicUuid(String namespace, String... components) {
        var seed = namespace + "|" + String.join("|", components);
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
    }
}
