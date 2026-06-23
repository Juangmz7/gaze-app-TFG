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
import com.app.socialservice.user.domain.model.User;
import com.app.socialservice.user.domain.model.valueobj.Email;
import com.app.socialservice.user.domain.model.valueobj.UserId;
import com.app.socialservice.user.domain.model.valueobj.Username;
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

    @Transactional
    public void registerUser(UserRegisterCommand command) {
        validateRegisterCommand(command);

        if (userRepository.existsById(command.userId())) {
            log.warn("Detected user {} already exists for event: {} with correlationId: {}, discarding message...",
                    command.userId(), command.id(), command.correlationId());
            return;
        }

        var savedUser = createAndSaveUser(command);

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

    private User createAndSaveUser(UserRegisterCommand command) {
        log.debug("Starting user {} registration", command.userId());
        var user = new User(
                new UserId(command.userId()),
                new Username(command.username()),
                new Email(command.email())
        );
        return userRepository.save(user);
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
        user.get().updateAuthInfo(username, email);
        var savedUser = userRepository.save(user.get());

        var occurredOn = Instant.now();
        var outboxEvent = createAndSaveOutboxEvent(command, savedUser, occurredOn);

        log.info("User {} auth info updated successfully for event: {} with correlationId: {}",
                command.userId(), outboxEvent.getId(), command.correlationId());

        eventPublisher.publishEvent(new UserAuthInfoUpdatedDomainEvent(
                outboxEvent.getId(),
                savedUser.getId(),
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

        user.get().delete();
        var savedUser = userRepository.save(user.get());

        var occurredOn = Instant.now();
        var outboxEvent = createAndSaveOutboxEvent(command, savedUser, occurredOn);

        log.info("User {} deleted successfully for event: {} with correlationId: {}",
                command.userId(), outboxEvent.getId(), command.correlationId());

        eventPublisher.publishEvent(new UserDeletedDomainEvent(
                outboxEvent.getId(),
                savedUser.getId(),
                occurredOn
        ));
    }

    private OutboxEvent createAndSaveOutboxEvent(DeleteUserCommand command, User savedUser, Instant occurredOn) {
        var userDeletedEvent = userEventMapper.toUserDeleted(
                UUID.randomUUID(),
                command.correlationId(),
                savedUser,
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
}
