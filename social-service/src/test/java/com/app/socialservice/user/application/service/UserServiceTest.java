package com.app.socialservice.user.application.service;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.app.socialservice.shared.infrastructure.entity.OutboxEvent;
import com.app.socialservice.shared.infrastructure.enums.EventStatus;
import com.app.socialservice.shared.infrastructure.mapper.JsonMapper;
import com.app.socialservice.shared.infrastructure.repository.OutboxEventRepository;
import com.app.socialservice.user.application.commands.DeleteUserCommand;
import com.app.socialservice.user.application.commands.UpdateOwnUserProfileCommand;
import com.app.socialservice.user.application.commands.UpdateAuthUserInfoCommand;
import com.app.socialservice.user.application.dto.OwnUserProfileResponse;
import com.app.socialservice.user.application.commands.UserRegisterCommand;
import com.app.socialservice.user.application.dto.OwnUserProfileData;
import com.app.socialservice.user.application.repository.UserStatsRepository;
import com.app.socialservice.user.application.repository.UserRepository;
import com.app.socialservice.user.domain.exception.UserNotFoundException;
import com.app.socialservice.user.domain.events.UserAuthInfoUpdatedDomainEvent;
import com.app.socialservice.user.domain.events.UserDeletedDomainEvent;
import com.app.socialservice.user.domain.events.UserRegisteredDomainEvent;
import com.app.socialservice.user.domain.enums.UserAccountStatus;
import com.app.socialservice.user.domain.model.User;
import com.app.socialservice.user.domain.model.valueobj.Email;
import com.app.socialservice.user.domain.model.valueobj.ProfilePictureUrl;
import com.app.socialservice.user.domain.model.valueobj.UserBio;
import com.app.socialservice.user.domain.model.valueobj.UserId;
import com.app.socialservice.user.domain.model.valueobj.Username;
import com.app.socialservice.user.infrastructure.events.UserBioEventPayload;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

    @Mock
    private UserStatsRepository userStatsRepository;

    @InjectMocks
    private UserService userService;

    @Test
    void shouldReturnOwnProfileUsingRedisCountersAndPostgresFields() {
        var userId = UUID.randomUUID();
        var ownProfileData = new OwnUserProfileData(
                "profile-user",
                "Persisted bio",
                java.util.Map.of("github", "profile-user"),
                7L,
                "https://example.com/avatar.png",
                true
        );

        when(userRepository.findOwnProfileById(userId)).thenReturn(Optional.of(ownProfileData));
        when(userStatsRepository.getFollowersCount(userId)).thenReturn(11L);
        when(userStatsRepository.getFollowingCount(userId)).thenReturn(13L);

        var response = userService.getOwnProfile(userId);

        assertThat(response.username()).isEqualTo("profile-user");
        assertThat(response.description()).isEqualTo("Persisted bio");
        assertThat(response.socialMedia()).containsEntry("github", "profile-user");
        assertThat(response.followersCount()).isEqualTo(11L);
        assertThat(response.followingCount()).isEqualTo(13L);
        assertThat(response.postCount()).isEqualTo(7L);
        assertThat(response.profilePic()).isEqualTo("https://example.com/avatar.png");
        assertThat(response.isBanned()).isTrue();
    }

    @Test
    void shouldThrowWhenOwnProfileUserDoesNotExist() {
        var userId = UUID.randomUUID();

        when(userRepository.findOwnProfileById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.getOwnProfile(userId))
                .isInstanceOf(UserNotFoundException.class)
                .hasMessage("User not found: " + userId);

        verifyNoInteractions(userStatsRepository);
    }

    @Test
    void shouldRejectNullUserIdWhenGettingOwnProfile() {
        assertThatThrownBy(() -> userService.getOwnProfile(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("userId must not be null");

        verifyNoInteractions(userRepository, userStatsRepository);
    void shouldUpdateOwnProfileWhenDataChanges() {
        var userId = UUID.randomUUID();
        var existingUser = buildUser(userId, "profile-user", "profile@example.com", UserAccountStatus.ACCEPTED);
        existingUser.setPictureUrl(new ProfilePictureUrl("https://cdn.example.com/old.png"));
        existingUser.setBio(new UserBio("Old bio", Map.of("github", "old-user")));
        var command = new UpdateOwnUserProfileCommand(
                userId,
                "New bio",
                "https://cdn.example.com/new.png",
                Map.of("github", "new-user", "linkedin", "profile-user")
        );
        var savedUser = buildUser(userId, "profile-user", "profile@example.com", UserAccountStatus.ACCEPTED);
        savedUser.setPictureUrl(new ProfilePictureUrl("https://cdn.example.com/new.png"));
        savedUser.setBio(new UserBio("New bio", Map.of(
                "github", "new-user",
                "linkedin", "profile-user"
        )));

        when(userRepository.findById(userId)).thenReturn(Optional.of(existingUser));
        when(userRepository.updateProfile(any(User.class))).thenReturn(savedUser);

        var response = userService.updateOwnUserProfile(command);

        verify(userRepository).updateProfile(existingUser);
        assertThat(response).isEqualTo(new OwnUserProfileResponse(
                userId,
                "New bio",
                "https://cdn.example.com/new.png",
                Map.of(
                        "github", "new-user",
                        "linkedin", "profile-user"
                )
        ));
    }

    @Test
    void shouldNotUpdateOwnProfileWhenDataIsUnchanged() {
        var userId = UUID.randomUUID();
        var existingUser = buildUser(userId, "same-user", "same@example.com", UserAccountStatus.ACCEPTED);
        existingUser.setPictureUrl(new ProfilePictureUrl("https://cdn.example.com/same.png"));
        existingUser.setBio(new UserBio("Same bio", Map.of("github", "same-user")));
        var command = new UpdateOwnUserProfileCommand(
                userId,
                "Same bio",
                "https://cdn.example.com/same.png",
                Map.of("github", "same-user")
        );

        when(userRepository.findById(userId)).thenReturn(Optional.of(existingUser));

        var response = userService.updateOwnUserProfile(command);

        verify(userRepository, never()).updateProfile(any(User.class));
        assertThat(response).isEqualTo(new OwnUserProfileResponse(
                userId,
                "Same bio",
                "https://cdn.example.com/same.png",
                Map.of("github", "same-user")
        ));
        verifyNoInteractions(outboxEventRepository, userEventMapper, jsonMapper, eventPublisher);
    }

    @Test
    void shouldThrowValidationErrorForInvalidOwnProfileInputs() {
        var blankDescriptionCommand = new UpdateOwnUserProfileCommand(
                UUID.randomUUID(),
                "   ",
                null,
                Map.of()
        );
        var overlongDescriptionCommand = new UpdateOwnUserProfileCommand(
                UUID.randomUUID(),
                "a".repeat(UserBio.MAX_DESCRIPTION_LENGTH + 1),
                null,
                Map.of()
        );
        var overlongProfilePictureCommand = new UpdateOwnUserProfileCommand(
                UUID.randomUUID(),
                "Valid bio",
                "https://%s".formatted("a".repeat(ProfilePictureUrl.MAX_LENGTH)),
                Map.of("github", "valid-user")
        );
        var invalidUrlCommand = new UpdateOwnUserProfileCommand(
                UUID.randomUUID(),
                "Valid bio",
                "ftp://cdn.example.com/profile.png",
                Map.of("github", "valid-user")
        );

        assertThatThrownBy(() -> userService.updateOwnUserProfile(blankDescriptionCommand))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("command.description must not be blank");

        assertThatThrownBy(() -> userService.updateOwnUserProfile(overlongDescriptionCommand))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("command.description must not exceed 255 characters");

        assertThatThrownBy(() -> userService.updateOwnUserProfile(overlongProfilePictureCommand))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("command.profilePicture must not exceed 255 characters");

        when(userRepository.findById(invalidUrlCommand.userId()))
                .thenReturn(Optional.of(buildUser(
                        invalidUrlCommand.userId(),
                        "valid-user",
                        "valid@example.com",
                        UserAccountStatus.ACCEPTED
                )));

        assertThatThrownBy(() -> userService.updateOwnUserProfile(invalidUrlCommand))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Profile picture URL");

        verify(userRepository, never()).updateProfile(any(User.class));
    }

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
                .bio(UserBioEventPayload.builder()
                        .description("Registered bio")
                        .socialMedia(java.util.Map.of("github", "registered-user"))
                        .build())
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
                .bio(UserBioEventPayload.builder()
                        .description("Updated bio")
                        .socialMedia(java.util.Map.of("github", "new-name"))
                        .build())
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
        user.setBio(new UserBio("Persisted bio", Map.of("github", username)));
        return user;
    }
}
