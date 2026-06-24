package com.app.socialservice.user.application.service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.app.socialservice.shared.infrastructure.entity.OutboxEvent;
import com.app.socialservice.shared.infrastructure.enums.EventStatus;
import com.app.socialservice.shared.infrastructure.mapper.JsonMapper;
import com.app.socialservice.shared.infrastructure.repository.OutboxEventRepository;
import com.app.socialservice.user.application.commands.DeleteUserCommand;
import com.app.socialservice.user.application.commands.UpdateAuthUserInfoCommand;
import com.app.socialservice.user.application.commands.UserRegisterCommand;
import com.app.socialservice.user.application.repository.UserRepository;
import com.app.socialservice.user.domain.events.UserAuthInfoUpdatedDomainEvent;
import com.app.socialservice.user.domain.events.UserDeletedDomainEvent;
import com.app.socialservice.user.domain.events.UserRegisteredDomainEvent;
import com.app.socialservice.user.domain.enums.UserAccountStatus;
import com.app.socialservice.user.domain.model.User;
import com.app.socialservice.user.domain.model.valueobj.Email;
import com.app.socialservice.user.domain.model.valueobj.UserId;
import com.app.socialservice.user.domain.model.valueobj.Username;
import com.app.socialservice.user.infrastructure.events.UserDeletedEvent;
import com.app.socialservice.user.infrastructure.events.UserRegisteredEvent;
import com.app.socialservice.user.infrastructure.events.UserUpdatedEvent;
import com.app.socialservice.user.infrastructure.mapper.UserEventMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private UserEventMapper userEventMapper;

    @Mock
    private JsonMapper jsonMapper;

    @InjectMocks
    private UserService userService;

    @Test
    void shouldRegisterUserSuccessfullyAndPublishDomainEventWhenUserDoesNotExist() {
        var userId = UUID.randomUUID();
        var correlationId = UUID.randomUUID();
        var command = new UserRegisterCommand(
                UUID.randomUUID(),
                correlationId,
                userId,
                "registered-user",
                "registered@example.com",
                Instant.now(),
                "UserRegisteredFromAuthEvent"
        );
        var savedUser = buildUser(userId, "registered-user", "registered@example.com", UserAccountStatus.ACCEPTED);
        var mappedEvent = UserRegisteredEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(correlationId)
                .occurredAt(Instant.now())
                .userId(userId)
                .username("registered-user")
                .email("registered@example.com")
                .build();

        when(userRepository.insertIfAbsent(any(User.class))).thenReturn(true);
        when(userRepository.findById(userId)).thenReturn(Optional.of(savedUser));
        when(userEventMapper.toUserRegisteredEvent(any(), any(), any(User.class), any())).thenReturn(mappedEvent);
        when(jsonMapper.toJson(mappedEvent)).thenReturn("{\"type\":\"registered\"}");
        when(outboxEventRepository.save(any(OutboxEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));

        userService.registerUser(command);

        verify(userRepository).insertIfAbsent(any(User.class));

        var outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().getCorrelationId()).isEqualTo(correlationId);
        assertThat(outboxCaptor.getValue().getStatus()).isEqualTo(EventStatus.PENDING);
        assertThat(outboxCaptor.getValue().getEventType()).isEqualTo(UserRegisteredEvent.class.getSimpleName());

        var domainEventCaptor = ArgumentCaptor.forClass(UserRegisteredDomainEvent.class);
        verify(eventPublisher).publishEvent(domainEventCaptor.capture());
        assertThat(domainEventCaptor.getValue().userId().value()).isEqualTo(userId);
        assertThat(domainEventCaptor.getValue().id()).isEqualTo(outboxCaptor.getValue().getId());
    }

    @Test
    void shouldNotRegisterUserWhenUserAlreadyExists() {
        var userId = UUID.randomUUID();
        var command = new UserRegisterCommand(
                UUID.randomUUID(),
                UUID.randomUUID(),
                userId,
                "existing-user",
                "existing@example.com",
                Instant.now(),
                "UserRegisteredFromAuthEvent"
        );

        when(userRepository.insertIfAbsent(any(User.class))).thenReturn(false);

        userService.registerUser(command);

        verify(userRepository).insertIfAbsent(any(User.class));
        verify(userRepository, never()).findById(any(UUID.class));
        verifyNoInteractions(outboxEventRepository, userEventMapper, jsonMapper, eventPublisher);
    }

    @Test
    void shouldUpdateUserAuthInfoAndPublishDomainEventWhenThereAreChanges() {
        var userId = UUID.randomUUID();
        var correlationId = UUID.randomUUID();
        var existingUser = buildUser(userId, "old-name", "old@example.com", UserAccountStatus.ACCEPTED);
        var command = new UpdateAuthUserInfoCommand(
                UUID.randomUUID(),
                correlationId,
                userId,
                "new-name",
                "new@example.com",
                Instant.now(),
                "UserInfoFromAuthUpdatedEvent"
        );
        var mappedEvent = UserUpdatedEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(correlationId)
                .occurredAt(Instant.now())
                .userId(userId)
                .username("new-name")
                .email("new@example.com")
                .accountStatus(UserAccountStatus.ACCEPTED.name())
                .build();

        when(userRepository.findById(userId)).thenReturn(Optional.of(existingUser));
        when(userRepository.updateAuthInfo(userId, "new-name", "new@example.com")).thenReturn(true);
        when(userEventMapper.toUserUpdated(any(), any(), any(User.class), any())).thenReturn(mappedEvent);
        when(jsonMapper.toJson(mappedEvent)).thenReturn("{\"type\":\"updated\"}");
        when(outboxEventRepository.save(any(OutboxEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));

        userService.updateUserAuthInfo(command);

        verify(userRepository).updateAuthInfo(userId, "new-name", "new@example.com");

        var domainEventCaptor = ArgumentCaptor.forClass(UserAuthInfoUpdatedDomainEvent.class);
        verify(eventPublisher).publishEvent(domainEventCaptor.capture());
        assertThat(domainEventCaptor.getValue().userId().value()).isEqualTo(userId);

        var outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().getEventType()).isEqualTo(UserUpdatedEvent.class.getSimpleName());
    }

    @Test
    void shouldIgnoreUpdateWhenUserDoesNotExistOrHasNoChangesInAuthInfo() {
        var missingCommand = new UpdateAuthUserInfoCommand(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "missing-user",
                "missing@example.com",
                Instant.now(),
                "UserInfoFromAuthUpdatedEvent"
        );
        var userId = UUID.randomUUID();
        var unchangedUser = buildUser(userId, "same-name", "same@example.com", UserAccountStatus.ACCEPTED);
        var unchangedCommand = new UpdateAuthUserInfoCommand(
                UUID.randomUUID(),
                UUID.randomUUID(),
                userId,
                "same-name",
                "same@example.com",
                Instant.now(),
                "UserInfoFromAuthUpdatedEvent"
        );

        when(userRepository.findById(missingCommand.userId())).thenReturn(Optional.empty());
        when(userRepository.findById(userId)).thenReturn(Optional.of(unchangedUser));

        userService.updateUserAuthInfo(missingCommand);
        userService.updateUserAuthInfo(unchangedCommand);

        verify(userRepository, never()).updateAuthInfo(any(), any(), any());
        verifyNoInteractions(outboxEventRepository, userEventMapper, jsonMapper, eventPublisher);
    }

    @Test
    void shouldDeleteUserAndPublishDomainEventWhenUserExists() {
        var userId = UUID.randomUUID();
        var correlationId = UUID.randomUUID();
        var existingUser = buildUser(userId, "delete-me", "delete@example.com", UserAccountStatus.ACCEPTED);
        var command = new DeleteUserCommand(
                UUID.randomUUID(),
                correlationId,
                userId,
                Instant.now(),
                "UserDeletedFromAuthEvent"
        );
        var mappedEvent = UserDeletedEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(correlationId)
                .occurredAt(Instant.now())
                .userId(userId)
                .build();

        when(userRepository.findById(userId)).thenReturn(Optional.of(existingUser));
        when(userRepository.deleteAndObfuscate(userId)).thenReturn(true);
        when(userEventMapper.toUserDeleted(any(), any(), any(UUID.class), any())).thenReturn(mappedEvent);
        when(jsonMapper.toJson(mappedEvent)).thenReturn("{\"type\":\"deleted\"}");
        when(outboxEventRepository.save(any(OutboxEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));

        userService.deleteUser(command);

        verify(userRepository).deleteAndObfuscate(userId);

        var domainEventCaptor = ArgumentCaptor.forClass(UserDeletedDomainEvent.class);
        verify(eventPublisher).publishEvent(domainEventCaptor.capture());
        assertThat(domainEventCaptor.getValue().userId().value()).isEqualTo(userId);

        var outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().getEventType()).isEqualTo(UserDeletedEvent.class.getSimpleName());
    }

    @Test
    void shouldIgnoreDeleteWhenUserDoesNotExist() {
        var command = new DeleteUserCommand(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                Instant.now(),
                "UserDeletedFromAuthEvent"
        );

        when(userRepository.findById(command.userId())).thenReturn(Optional.empty());

        userService.deleteUser(command);

        verify(userRepository, never()).deleteAndObfuscate(any());
        verifyNoInteractions(outboxEventRepository, userEventMapper, jsonMapper, eventPublisher);
    }

    private User buildUser(UUID userId, String username, String email, UserAccountStatus status) {
        var user = new User(
                new UserId(userId),
                new Username(username),
                new Email(email)
        );
        user.setAccountStatus(status);
        return user;
    }
}
