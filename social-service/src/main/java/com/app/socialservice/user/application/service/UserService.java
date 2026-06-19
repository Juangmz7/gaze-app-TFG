package com.app.socialservice.user.application.service;

import com.app.socialservice.shared.infrastructure.entity.OutboxEvent;
import com.app.socialservice.shared.infrastructure.entity.ProcessedEvent;
import com.app.socialservice.shared.infrastructure.enums.EventStatus;
import com.app.socialservice.shared.infrastructure.mapper.JsonMapper;
import com.app.socialservice.shared.infrastructure.repository.OutboxEventRepository;
import com.app.socialservice.shared.infrastructure.repository.ProcessedEventsRepository;
import com.app.socialservice.user.application.commands.UserRegisterCommand;
import com.app.socialservice.user.domain.events.UserRegisteredDomainEvent;
import com.app.socialservice.user.domain.model.User;
import com.app.socialservice.user.domain.model.valueobj.Email;
import com.app.socialservice.user.domain.model.valueobj.UserId;
import com.app.socialservice.user.domain.model.valueobj.Username;
import com.app.socialservice.user.infrastructure.mapper.UserEventMapper;
import com.app.socialservice.user.infrastructure.events.UserRegisteredEvent;
import com.app.socialservice.user.application.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;


@Slf4j
@RequiredArgsConstructor
@Service
public class UserService {

    private final UserRepository userRepository;
    private final ProcessedEventsRepository processedEventsRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final OutboxEventRepository outboxEventRepository;
    private final UserEventMapper userEventMapper;
    private final JsonMapper jsonMapper;

    @Transactional
    public void registerUser(UserRegisterCommand command) {
        if (userRepository.existsById(command.userId())) {
            log.warn("Detected user {} already exists, discarding message...", command.userId());
            return;
        }
        if (processedEventsRepository.existsById(command.correlationId())) {
            log.warn("Detected event {} with correlationId {} duplication, discarding message...",
                    command.id(), command.correlationId());
            return;
        }

        var savedUser = createAndSaveUser(command);

        setEventAsProcessed(
                command.id(),
                command.correlationId(),
                command.eventType()
        );

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

    public void updateUserAuthInfo(UpdateAuthUserInfoCommand command) {
        Optional<User> user = getUserById(command.userId());
        if (user.isEmpty()) {
            log.warn("Detected user {} does not exists for event: {} with correlationId: {}, discarding message...",
                    command.userId(), command.id(), command.correlationId());
            return;
        }
        if (processedEventsRepository.existsById(command.correlationId())) {
            log.warn("Detected event {} with correlationId {} duplication, discarding message...",
                    command.id(), command.correlationId());            return;
        }

        var username = new Username(command.username());
        var email = new Username(command.email());
        var hasChanges = user.hasChanges(username, email);
        if (!hasChanges) {
            log.warn("Detected user {} does not need an update, discarding message...", command.correlationId());
            return;
        }
        user.updateAuthInfo(username, email);
        var savedUser = userRepository.save(user.get());

        setEventAsProcessed(
                command.id(),
                command.correlationId(),
                command.eventType()
        );

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
        var userAuthInfoUpdatedEvent = userEventMapper.toUserAuthInfoUpdated(
                UUID.randomUUID(),
                command.correlationId(),
                savedUser,
                occurredOn
        );
        var payload = jsonMapper.toJson(userAuthInfoUpdated);
        return outboxEventRepository.save(
                OutboxEvent.builder()
                        .id(UUID.randomUUID())
                        .correlationId(command.correlationId())
                        .payload(payload)
                        .eventType(UserAuthInfoUpdatedEvent.class.getSimpleName())
                        .status(EventStatus.PENDING)
                        .createdAt(occurredOn)
                        .build()
        );
    }

    public void deleteUser(DeleteUserCommand command) {
        Optional<User> user = getUserById(command.userId());
        if (user.isEmpty()) {
            log.warn("Detected user {} does not exists for event: {} with correlationId: {}, discarding message...",
                    command.userId(), command.id(), command.correlationId());
            return;
        }
        if (processedEventsRepository.existsById(command.correlationId())) {
            log.warn("Detected event {} with correlationId {} duplication, discarding message...",
                    command.id(), command.correlationId());
            return;
        }

        user.get().delete();
        var savedUser = userRepository.save(user.get());

        setEventAsProcessed(
                command.id(),
                command.correlationId(),
                command.eventType()
        );

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

    private void setEventAsProcessed(UUID eventId, UUID correlationId, String eventType) {
        processedEventsRepository.save(
                new ProcessedEvent(eventId, correlationId, eventType)
        );
    }

    private Optional<User> getUserById(UUID id) {
        return userRepository.findById(command.userId());
    }

}