package com.app.socialservice.shared.infrastructure.rabbitmq.listener;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import com.app.socialservice.shared.infrastructure.entity.ProcessedEvent;
import com.app.socialservice.shared.infrastructure.rabbitmq.config.RabbitMQProperties;
import com.app.socialservice.shared.infrastructure.repository.ProcessedEventsRepository;
import com.app.socialservice.user.application.commands.DeleteUserCommand;
import com.app.socialservice.user.application.commands.SynchroniseSecondaryDatabaseCommand;
import com.app.socialservice.user.application.commands.UpdateAuthUserInfoCommand;
import com.app.socialservice.user.application.commands.UserRegisterCommand;
import com.app.socialservice.user.application.service.UserNodeService;
import com.app.socialservice.user.application.service.UserService;
import com.app.socialservice.user.infrastructure.events.UserDeletedEvent;
import com.app.socialservice.user.infrastructure.events.UserDeletedFromAuthEvent;
import com.app.socialservice.user.infrastructure.events.UserInfoFromAuthUpdatedEvent;
import com.app.socialservice.user.infrastructure.events.UserRegisteredEvent;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserRabbitMQListenerTest {

    @Mock
    private UserService userService;

    @Mock
    private UserRegisterCommandMapper userRegisterCommandMapper;

    @Mock
    private UserNodeService userNodeService;

    @Mock
    private ProcessedEventsRepository processedEventsRepository;

    @Spy
    private RabbitMQProperties rabbitMQProperties = new RabbitMQProperties();

    @InjectMocks
    private UserRabbitMQListener userRabbitMQListener;

    @BeforeEach
    void setUp() {
        rabbitMQProperties.getQueue().getAuth().setRegister("q.social-service.auth.register");
        rabbitMQProperties.getQueue().getAuth().setUpdate("q.social-service.auth.update");
        rabbitMQProperties.getQueue().getAuth().setDelete("q.social-service.auth.delete");
        rabbitMQProperties.getQueue().getUser().setRegister("q.social-service.user.register");
        rabbitMQProperties.getQueue().getUser().setDeleted("q.social-service.user.deleted");
    }

    @Test
    void shouldValidateInputFieldsForUserAuthEventsInUserRabbitMqListener() {
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

        assertThatThrownBy(() -> userRabbitMQListener.onUserRegisteredFromAuth(invalidRegisterEvent))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("event.userId must be a valid UUID");

        assertThatThrownBy(() -> userRabbitMQListener.onUserInfoFromAuthUpdated(invalidUpdateEvent))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("event.details must not be null");

        assertThatThrownBy(() -> userRabbitMQListener.onUserDeletedFromAuth(invalidDeleteEvent))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("event.type must not be blank");
    }

    @Test
    void shouldDiscardRegisterMessageIfAlreadyProcessedInUserRabbitMqListener() {
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

        userRabbitMQListener.onUserRegisteredFromAuth(event);

        verify(userRegisterCommandMapper, never()).toCommand(any(), any(), any(), any());
        verify(userService, never()).registerUser(any());
        verify(processedEventsRepository, never()).insertIfAbsent(any(), any(), any());
    }

    @Test
    void shouldMarkRegisterMessageAsProcessedAfterSuccessfulServiceExecutionInUserRabbitMqListener() {
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

        userRabbitMQListener.onUserRegisteredFromAuth(event);

        verify(userService).registerUser(command);

        verify(processedEventsRepository).insertIfAbsent(
                expectedEventId,
                expectedCorrelationId,
                UserRegisteredFromAuthEvent.class.getSimpleName()
        );
    }

    @Test
    void shouldMarkUpdateAuthMessageAsProcessedAfterSuccessfulServiceExecutionInUserRabbitMqListener() {
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

        userRabbitMQListener.onUserInfoFromAuthUpdated(event);

        var commandCaptor = ArgumentCaptor.forClass(UpdateAuthUserInfoCommand.class);
        verify(userService).updateUserAuthInfo(commandCaptor.capture());
        assertThat(commandCaptor.getValue().id()).isEqualTo(expectedEventId);
        assertThat(commandCaptor.getValue().correlationId()).isEqualTo(expectedCorrelationId);
        assertThat(commandCaptor.getValue().userId()).isEqualTo(UUID.fromString(event.userId()));
        assertThat(commandCaptor.getValue().username()).isEqualTo(event.details().username());
        assertThat(commandCaptor.getValue().email()).isEqualTo(event.details().email());

        verify(processedEventsRepository).insertIfAbsent(
                expectedEventId,
                expectedCorrelationId,
                UserInfoFromAuthUpdatedEvent.class.getSimpleName()
        );
    }

    @Test
    void shouldDiscardUpdateAuthMessageWhenAlreadyProcessedInUserRabbitMqListener() {
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

        userRabbitMQListener.onUserInfoFromAuthUpdated(event);

        verify(userService, never()).updateUserAuthInfo(any(UpdateAuthUserInfoCommand.class));
        verify(processedEventsRepository, never()).insertIfAbsent(any(), any(), any());
    }

    @Test
    void shouldMarkDeleteAuthMessageAsProcessedAfterSuccessfulServiceExecutionInUserRabbitMqListener() {
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

        userRabbitMQListener.onUserDeletedFromAuth(event);

        var commandCaptor = ArgumentCaptor.forClass(DeleteUserCommand.class);
        verify(userService).deleteUser(commandCaptor.capture());
        assertThat(commandCaptor.getValue().id()).isEqualTo(expectedEventId);
        assertThat(commandCaptor.getValue().correlationId()).isEqualTo(expectedCorrelationId);
        assertThat(commandCaptor.getValue().userId()).isEqualTo(UUID.fromString(event.userId()));

        verify(processedEventsRepository).insertIfAbsent(
                expectedEventId,
                expectedCorrelationId,
                UserDeletedFromAuthEvent.class.getSimpleName()
        );
    }

    @Test
    void shouldDiscardDeleteAuthMessageWhenAlreadyProcessedInUserRabbitMqListener() {
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

        userRabbitMQListener.onUserDeletedFromAuth(event);

        verify(userService, never()).deleteUser(any(DeleteUserCommand.class));
        verify(processedEventsRepository, never()).insertIfAbsent(any(), any(), any());
        verify(processedEventsRepository, never()).existsByCorrelationId(expectedCorrelationId);
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
                        "Test",
                        "User",
                        "listener-it@example.com",
                        "listener-it"
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

    @Test
    void shouldValidateInputFieldsForUserEventsInUserRabbitMqListener() {
        var invalidRegisteredEvent = UserRegisteredEvent.builder().build();
        var invalidDeletedEvent = UserDeletedEvent.builder().build();

        assertThatThrownBy(() -> userRabbitMQListener.syncSecondaryDatabase(invalidRegisteredEvent))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("event.id must not be null");

        assertThatThrownBy(() -> userRabbitMQListener.onUserDeleted(invalidDeletedEvent))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("event.id must not be null");
    }

    @Test
    void shouldDiscardSyncSecondaryDatabaseMessageIfAlreadyProcessedInUserRabbitMqListener() {
        var eventId = UUID.randomUUID();
        var correlationId = UUID.randomUUID();
        var event = new UserRegisteredEvent(eventId, correlationId, Instant.now(), UUID.randomUUID(), "user", "test@test.com");

        when(processedEventsRepository.existsById(eventId)).thenReturn(false);
        when(processedEventsRepository.existsByCorrelationId(correlationId)).thenReturn(true);

        userRabbitMQListener.syncSecondaryDatabase(event);

        verify(userNodeService, never()).registerUserNode(any());
        verify(processedEventsRepository, never()).insertIfAbsent(any(), any(), any());
    }

    @Test
    void shouldMarkSyncSecondaryDatabaseMessageAsProcessedAfterSuccessfulServiceExecutionInUserRabbitMqListener() {
        var eventId = UUID.randomUUID();
        var correlationId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var event = new UserRegisteredEvent(eventId, correlationId, Instant.now(), userId, "user", "test@test.com");

        when(processedEventsRepository.existsById(eventId)).thenReturn(false);
        when(processedEventsRepository.existsByCorrelationId(correlationId)).thenReturn(false);

        userRabbitMQListener.syncSecondaryDatabase(event);

        var commandCaptor = ArgumentCaptor.forClass(SynchroniseSecondaryDatabaseCommand.class);
        verify(userNodeService).registerUserNode(commandCaptor.capture());
        assertThat(commandCaptor.getValue().eventId()).isEqualTo(eventId);
        assertThat(commandCaptor.getValue().correlationId()).isEqualTo(correlationId);
        assertThat(commandCaptor.getValue().userId()).isEqualTo(userId);

        verify(processedEventsRepository).insertIfAbsent(
                eventId,
                correlationId,
                UserRegisteredEvent.class.getSimpleName()
        );
    }

    @Test
    void shouldDiscardOnUserDeletedMessageIfAlreadyProcessedInUserRabbitMqListener() {
        var eventId = UUID.randomUUID();
        var correlationId = UUID.randomUUID();
        var event = new UserDeletedEvent(eventId, correlationId, Instant.now(), UUID.randomUUID());

        when(processedEventsRepository.existsById(eventId)).thenReturn(true);

        userRabbitMQListener.onUserDeleted(event);

        verify(userNodeService, never()).deleteUserNode(any());
        verify(processedEventsRepository, never()).insertIfAbsent(any(), any(), any());
    }

    @Test
    void shouldMarkOnUserDeletedMessageAsProcessedAfterSuccessfulServiceExecutionInUserRabbitMqListener() {
        var eventId = UUID.randomUUID();
        var correlationId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var event = new UserDeletedEvent(eventId, correlationId, Instant.now(), userId);

        when(processedEventsRepository.existsById(eventId)).thenReturn(false);
        when(processedEventsRepository.existsByCorrelationId(correlationId)).thenReturn(false);

        userRabbitMQListener.onUserDeleted(event);

        var commandCaptor = ArgumentCaptor.forClass(SynchroniseSecondaryDatabaseCommand.class);
        verify(userNodeService).deleteUserNode(commandCaptor.capture());
        assertThat(commandCaptor.getValue().eventId()).isEqualTo(eventId);
        assertThat(commandCaptor.getValue().correlationId()).isEqualTo(correlationId);
        assertThat(commandCaptor.getValue().userId()).isEqualTo(userId);

        verify(processedEventsRepository).insertIfAbsent(
                eventId,
                correlationId,
                UserDeletedEvent.class.getSimpleName()
        );
    }
}
