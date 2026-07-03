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
import com.app.socialservice.user.application.commands.UserRegisterCommand;
import com.app.socialservice.user.application.dto.OwnUserProfileResponse;
import com.app.socialservice.user.application.repository.UserRepository;
import com.app.socialservice.user.application.repository.UserStatsRepository;
import com.app.socialservice.user.domain.exception.UserNotFoundException;
import com.app.socialservice.user.domain.events.UserAuthInfoUpdatedDomainEvent;
import com.app.socialservice.user.domain.events.UserDeletedDomainEvent;
import com.app.socialservice.user.domain.events.UserRegisteredDomainEvent;
import com.app.socialservice.user.domain.model.User;
import com.app.socialservice.user.domain.model.valueobj.Email;
import com.app.socialservice.user.domain.model.valueobj.ProfilePictureUrl;
import com.app.socialservice.user.domain.model.valueobj.UserBio;
import com.app.socialservice.user.domain.model.valueobj.UserId;
import com.app.socialservice.user.domain.model.valueobj.Username;
import com.app.socialservice.user.domain.exception.UserNotFoundException;
import com.app.socialservice.user.infrastructure.events.UserDeletedEvent;
import com.app.socialservice.user.infrastructure.events.UserRegisteredEvent;
import com.app.socialservice.user.infrastructure.events.UserUpdatedEvent;
import com.app.socialservice.user.infrastructure.mapper.UserEventMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@RequiredArgsConstructor
@Service
public class UserService {

    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final OutboxEventRepository outboxEventRepository;
    private final UserEventMapper userEventMapper;
    private final JsonMapper jsonMapper;
    private final UserStatsRepository userStatsRepository;

    @Transactional(readOnly = true)
    public OwnUserProfileResponse getOwnProfile(UUID userId) {
        validateUserId(userId);
        log.info("Retrieving own profile for user {}", userId);

        var userProfile = userRepository.findOwnProfileById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));

        var response = new OwnUserProfileResponse(
                userProfile.username(),
                userProfile.description(),
                userProfile.socialMedia(),
                userStatsRepository.getFollowersCount(userId),
                userStatsRepository.getFollowingCount(userId),
                userProfile.postCount(),
                userProfile.profilePic(),
                userProfile.banned()
        );

        log.info("Own profile retrieved for user {}", userId);
        return response;
    }

    @Transactional
    public OwnUserProfileResponse updateOwnUserProfile(UpdateOwnUserProfileCommand command) {
        validateUpdateOwnProfileCommand(command);

        var user = getUserById(command.userId())
                .orElseThrow(() -> new UserNotFoundException(command.userId()));
        var requestedPictureUrl = toProfilePictureUrl(command.profilePicture());
        var requestedBio = toUserBio(command.description(), command.socialMedia());

        if (!user.hasProfileChanges(requestedPictureUrl, requestedBio)) {
            return toOwnUserProfileResponse(user);
        }

        user.updateProfile(requestedPictureUrl, requestedBio);

        var savedUser = userRepository.updateProfile(user);
        return toOwnUserProfileResponse(savedUser);
    }

    @Transactional
    public void registerUser(UserRegisterCommand command) {
        validateRegisterCommand(command);

        var user = new User(
                new UserId(command.userId()),
                new Username(command.username()),
                new Email(command.email())
        );

        boolean inserted = userRepository.insertIfAbsent(user);
        if (!inserted) {
            log.warn("Detected user {} already exists for event: {} with correlationId: {}, discarding message...",
                    command.userId(), command.id(), command.correlationId());
            return;
        }

        var savedUser = userRepository.findById(user.getId().value()).orElseThrow();

        var occurredOn = Instant.now();
        var outboxEvent = createAndSaveOutboxEvent(command, savedUser, occurredOn);

        log.info("User {} registration completed successfully for event: {} with correlationId: {}",
                command.userId(), outboxEvent.getId(), command.correlationId());

        eventPublisher.publishEvent(new UserRegisteredDomainEvent(
                outboxEvent.getId(),
                savedUser.getId(),
                occurredOn
        ));
    }

    private OutboxEvent createAndSaveOutboxEvent(UserRegisterCommand command, User savedUser, Instant occurredOn) {
        var userRegisteredEvent = userEventMapper.toUserRegisteredEvent(
                UUID.randomUUID(),
                command.correlationId(),
                savedUser,
                occurredOn
        );
        var payload = jsonMapper.toJson(userRegisteredEvent);
        return outboxEventRepository.save(
                OutboxEvent.builder()
                        .id(UUID.randomUUID())
                        .correlationId(command.correlationId())
                        .payload(payload)
                        .eventType(UserRegisteredEvent.class.getSimpleName())
                        .status(EventStatus.PENDING)
                        .createdAt(occurredOn)
                        .build()
        );
    }

    @Transactional
    public void updateUserAuthInfo(UpdateAuthUserInfoCommand command) {
        validateUpdateCommand(command);

        Optional<User> user = getUserById(command.userId());
        if (user.isEmpty()) {
            log.warn("Detected user {} does not exists for event: {} with correlationId: {}, discarding message...",
                    command.userId(), command.id(), command.correlationId());
            return;
        }

        var username = new Username(command.username());
        var email = new Email(command.email());
        var hasChanges = user.get().hasChanges(username, email);
        if (!hasChanges) {
            log.warn("Detected user {} does not need an update for event: {} with correlationId: {}, discarding message...",
                    command.userId(), command.id(), command.correlationId());
            return;
        }
        boolean updated = userRepository.updateAuthInfo(command.userId(), command.username(), command.email());
        if (!updated) {
            log.warn("Concurrent modification or already updated for user {}, discarding update for event: {}", 
                    command.userId(), command.id());
            return;
        }

        user.get().updateAuthInfo(username, email);
        
        var occurredOn = Instant.now();
        var outboxEvent = createAndSaveOutboxEvent(command, user.get(), occurredOn);

        log.info("User {} auth info updated successfully for event: {} with correlationId: {}",
                command.userId(), outboxEvent.getId(), command.correlationId());

        eventPublisher.publishEvent(new UserAuthInfoUpdatedDomainEvent(
                outboxEvent.getId(),
                user.get().getId(),
                occurredOn
        ));
    }

    private OutboxEvent createAndSaveOutboxEvent(UpdateAuthUserInfoCommand command, User savedUser, Instant occurredOn) {
        var userUpdatedEvent = userEventMapper.toUserUpdated(
                UUID.randomUUID(),
                command.correlationId(),
                savedUser,
                occurredOn
        );
        var payload = jsonMapper.toJson(userUpdatedEvent);
        return outboxEventRepository.save(
                OutboxEvent.builder()
                        .id(UUID.randomUUID())
                        .correlationId(command.correlationId())
                        .payload(payload)
                        .eventType(UserUpdatedEvent.class.getSimpleName())
                        .status(EventStatus.PENDING)
                        .createdAt(occurredOn)
                        .build()
        );
    }

    @Transactional
    public void deleteUser(DeleteUserCommand command) {
        validateDeleteCommand(command);

        Optional<User> user = getUserById(command.userId());
        if (user.isEmpty()) {
            log.warn("Detected user {} does not exists for event: {} with correlationId: {}, discarding message...",
                    command.userId(), command.id(), command.correlationId());
            return;
        }

        boolean deleted = userRepository.deleteAndObfuscate(command.userId());
        if (!deleted) {
            log.warn("Concurrent modification or already deleted for user {}, discarding delete for event: {}", 
                    command.userId(), command.id());
            return;
        }
        
        var occurredOn = Instant.now();
        var outboxEvent = createAndSaveOutboxEvent(command, command.userId(), occurredOn);

        log.info("User {} deleted successfully for event: {} with correlationId: {}",
                command.userId(), outboxEvent.getId(), command.correlationId());

        eventPublisher.publishEvent(new UserDeletedDomainEvent(
                outboxEvent.getId(),
                new UserId(command.userId()),
                occurredOn
        ));
    }

    private OutboxEvent createAndSaveOutboxEvent(DeleteUserCommand command, UUID userId, Instant occurredOn) {
        var userDeletedEvent = userEventMapper.toUserDeleted(
                UUID.randomUUID(),
                command.correlationId(),
                userId,
                occurredOn
        );
        var payload = jsonMapper.toJson(userDeletedEvent);
        return outboxEventRepository.save(
                OutboxEvent.builder()
                        .id(UUID.randomUUID())
                        .correlationId(command.correlationId())
                        .payload(payload)
                        .eventType(UserDeletedEvent.class.getSimpleName())
                        .status(EventStatus.PENDING)
                        .createdAt(occurredOn)
                        .build()
        );
    }

    private Optional<User> getUserById(UUID id) {
        return userRepository.findById(id);
    }

    private OwnUserProfileResponse toOwnUserProfileResponse(User user) {
        var bio = user.getBio();
        return new OwnUserProfileResponse(
                user.getId().value(),
                bio == null ? null : bio.description(),
                user.getPictureUrl() == null ? null : user.getPictureUrl().value(),
                bio == null ? Map.of() : bio.socialMedia()
        );
    }

    private ProfilePictureUrl toProfilePictureUrl(String profilePicture) {
        if (profilePicture == null) {
            return null;
        }
        return new ProfilePictureUrl(profilePicture);
    }

    private UserBio toUserBio(String description, Map<String, String> socialMedia) {
        var normalizedSocialMedia = socialMedia == null ? Map.<String, String>of() : Map.copyOf(socialMedia);
        if (description == null && normalizedSocialMedia.isEmpty()) {
            return null;
        }
        return new UserBio(description, normalizedSocialMedia);
    }

    private void validateRegisterCommand(UserRegisterCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("command must not be null");
        }
        if (command.id() == null) {
            throw new IllegalArgumentException("command.id must not be null");
        }
        if (command.correlationId() == null) {
            throw new IllegalArgumentException("command.correlationId must not be null");
        }
        if (command.userId() == null) {
            throw new IllegalArgumentException("command.userId must not be null");
        }
        if (command.username() == null) {
            throw new IllegalArgumentException("command.username must not be null");
        }
        if (command.email() == null) {
            throw new IllegalArgumentException("command.email must not be null");
        }
        if (command.eventType() == null) {
            throw new IllegalArgumentException("command.eventType must not be null");
        }
    }

    private void validateUpdateOwnProfileCommand(UpdateOwnUserProfileCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("command must not be null");
        }
        if (command.userId() == null) {
            throw new IllegalArgumentException("command.userId must not be null");
        }
        if (command.description() != null && command.description().isBlank()) {
            throw new IllegalArgumentException("command.description must not be blank");
        }
        if (command.description() != null && command.description().length() > UserBio.MAX_DESCRIPTION_LENGTH) {
            throw new IllegalArgumentException(
                    "command.description must not exceed %d characters".formatted(UserBio.MAX_DESCRIPTION_LENGTH)
            );
        }
        if (command.profilePicture() != null && command.profilePicture().length() > ProfilePictureUrl.MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "command.profilePicture must not exceed %d characters".formatted(ProfilePictureUrl.MAX_LENGTH)
            );
        }
        if (command.socialMedia() != null) {
            command.socialMedia().forEach((platform, handle) -> {
                if (platform == null || platform.isBlank()) {
                    throw new IllegalArgumentException("command.socialMedia keys must not be null or blank");
                }
                if (handle == null || handle.isBlank()) {
                    throw new IllegalArgumentException("command.socialMedia values must not be null or blank");
                }
            });
        }
    }

    private void validateUpdateCommand(UpdateAuthUserInfoCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("command must not be null");
        }
        if (command.id() == null) {
            throw new IllegalArgumentException("command.id must not be null");
        }
        if (command.correlationId() == null) {
            throw new IllegalArgumentException("command.correlationId must not be null");
        }
        if (command.userId() == null) {
            throw new IllegalArgumentException("command.userId must not be null");
        }
        if (command.username() == null) {
            throw new IllegalArgumentException("command.username must not be null");
        }
        if (command.email() == null) {
            throw new IllegalArgumentException("command.email must not be null");
        }
        if (command.eventType() == null) {
            throw new IllegalArgumentException("command.eventType must not be null");
        }
    }

    private void validateDeleteCommand(DeleteUserCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("command must not be null");
        }
        if (command.id() == null) {
            throw new IllegalArgumentException("command.id must not be null");
        }
        if (command.correlationId() == null) {
            throw new IllegalArgumentException("command.correlationId must not be null");
        }
        if (command.userId() == null) {
            throw new IllegalArgumentException("command.userId must not be null");
        }
        if (command.eventType() == null) {
            throw new IllegalArgumentException("command.eventType must not be null");
        }
    }

    private void validateUserId(UUID userId) {
        if (userId == null) {
            throw new IllegalArgumentException("userId must not be null");
        }
    }
}
