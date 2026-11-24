package com.app.socialservice.user.application.service;

import java.time.Instant;
import java.util.UUID;

import com.app.socialservice.shared.infrastructure.entity.OutboxEvent;
import com.app.socialservice.shared.infrastructure.enums.EventStatus;
import com.app.socialservice.shared.infrastructure.mapper.JsonMapper;
import com.app.socialservice.shared.infrastructure.repository.OutboxEventRepository;
import com.app.socialservice.user.application.repository.UserRepository;
import com.app.socialservice.user.domain.events.UserProfileUpdatedDomainEvent;
import com.app.socialservice.user.domain.model.User;
import com.app.socialservice.user.infrastructure.events.UserUpdatedEvent;
import com.app.socialservice.user.infrastructure.mapper.UserEventMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional part of the own profile update: the profile write and its UserUpdatedEvent
 * outbox row commit together. Kept apart from UserProfileService so the Blob Storage check that
 * precedes it runs with no database transaction open.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OwnUserProfileWriter {

    private final UserRepository userRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final UserEventMapper userEventMapper;
    private final JsonMapper jsonMapper;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public User updateProfile(User user) {
        var updatedUser = userRepository.updateProfile(user);

        var occurredOn = Instant.now();
        var outboxEvent = createAndSaveOutboxEvent(updatedUser, occurredOn);

        log.info("User {} profile updated, UserUpdatedEvent {} saved with correlationId: {}",
                updatedUser.getId().value(), outboxEvent.getId(), outboxEvent.getCorrelationId());

        eventPublisher.publishEvent(new UserProfileUpdatedDomainEvent(
                outboxEvent.getId(),
                updatedUser.getId(),
                occurredOn
        ));
        return updatedUser;
    }

    // The PUT carries no correlation id, so the event starts a new chain
    private OutboxEvent createAndSaveOutboxEvent(User updatedUser, Instant occurredOn) {
        var correlationId = UUID.randomUUID();
        var userUpdatedEvent = userEventMapper.toUserUpdated(
                UUID.randomUUID(),
                correlationId,
                updatedUser,
                occurredOn
        );
        var payload = jsonMapper.toJson(userUpdatedEvent);
        return outboxEventRepository.save(
                OutboxEvent.builder()
                        .id(UUID.randomUUID())
                        .correlationId(correlationId)
                        .payload(payload)
                        .eventType(UserUpdatedEvent.class.getSimpleName())
                        .status(EventStatus.PENDING)
                        .createdAt(occurredOn)
                        .build()
        );
    }
}
