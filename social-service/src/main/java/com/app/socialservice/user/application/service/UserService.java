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


}
