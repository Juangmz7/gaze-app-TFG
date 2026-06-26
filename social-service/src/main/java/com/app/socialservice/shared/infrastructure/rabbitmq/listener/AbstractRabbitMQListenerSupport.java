package com.app.socialservice.shared.infrastructure.rabbitmq.listener;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import com.app.socialservice.block.infrastructure.events.UserBlockedEvent;
import com.app.socialservice.follow.infrastructure.events.UserFollowedEvent;
import com.app.socialservice.follow.infrastructure.events.UserUnfollowedEvent;
import com.app.socialservice.post.infrastructure.events.PostCreatedEvent;
import com.app.socialservice.post.infrastructure.events.PostDeletedEvent;
import com.app.socialservice.shared.infrastructure.entity.ProcessedEvent;
import com.app.socialservice.shared.infrastructure.rabbitmq.config.RabbitMQProperties;
import com.app.socialservice.shared.infrastructure.repository.ProcessedEventsRepository;
import com.app.socialservice.user.infrastructure.events.UserDeletedEvent;
import com.app.socialservice.user.infrastructure.events.UserDeletedFromAuthEvent;
import com.app.socialservice.user.infrastructure.events.UserInfoFromAuthUpdatedEvent;
import com.app.socialservice.user.infrastructure.events.UserRegisteredEvent;
import com.app.socialservice.user.infrastructure.events.UserRegisteredFromAuthEvent;
import org.springframework.util.StringUtils;

abstract class AbstractRabbitMQListenerSupport {

    private final ProcessedEventsRepository processedEventsRepository;
    protected final RabbitMQProperties rabbitMQProperties;

    protected AbstractRabbitMQListenerSupport(
            ProcessedEventsRepository processedEventsRepository,
            RabbitMQProperties rabbitMQProperties) {
        this.processedEventsRepository = processedEventsRepository;
        this.rabbitMQProperties = rabbitMQProperties;
    }

    protected final boolean isEventAlreadyProcessed(UUID eventId, UUID correlationId) {
        return processedEventsRepository.existsById(eventId)
                || processedEventsRepository.existsByCorrelationId(correlationId);
    }

    protected final void setEventAsProcessed(UUID eventId, UUID correlationId, String eventType) {
        processedEventsRepository.insertIfAbsent(
                eventId,
                correlationId,
                eventType
        );
    }

    protected final void validateUserBlockedEvent(UserBlockedEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("event must not be null");
        }
        if (event.id() == null) {
            throw new IllegalArgumentException("event.id must not be null");
        }
        if (event.correlationId() == null) {
            throw new IllegalArgumentException("event.correlationId must not be null");
        }
        if (event.blockerUserId() == null) {
            throw new IllegalArgumentException("event.blockerUserId must not be null");
        }
        if (event.blockedUserId() == null) {
            throw new IllegalArgumentException("event.blockedUserId must not be null");
        }
    }

    protected final void validateUserFollowedEvent(UserFollowedEvent event) {
        validateFollowEventEnvelope(
                event,
                event != null ? event.id() : null,
                event != null ? event.correlationId() : null,
                event != null ? event.occurredAt() : null,
                event != null ? event.followerUserId() : null,
                event != null ? event.followedUserId() : null
        );
    }

    protected final void validateUserUnfollowedEvent(UserUnfollowedEvent event) {
        validateFollowEventEnvelope(
                event,
                event != null ? event.id() : null,
                event != null ? event.correlationId() : null,
                event != null ? event.occurredAt() : null,
                event != null ? event.followerUserId() : null,
                event != null ? event.followedUserId() : null
        );
    }

    private void validateFollowEventEnvelope(
            Object event,
            UUID eventId,
            UUID correlationId,
            java.time.Instant occurredAt,
            UUID followerUserId,
            UUID followedUserId) {
        if (event == null) {
            throw new IllegalArgumentException("event must not be null");
        }
        if (eventId == null) {
            throw new IllegalArgumentException("event.id must not be null");
        }
        if (correlationId == null) {
            throw new IllegalArgumentException("event.correlationId must not be null");
        }
        if (occurredAt == null) {
            throw new IllegalArgumentException("event.occurredAt must not be null");
        }
        if (followerUserId == null) {
            throw new IllegalArgumentException("event.followerUserId must not be null");
        }
        if (followedUserId == null) {
            throw new IllegalArgumentException("event.followedUserId must not be null");
        }
        if (followerUserId.equals(followedUserId)) {
            throw new IllegalArgumentException("event follower and followed users must be different");
        }
    }

    protected final void validatePostCreatedEvent(PostCreatedEvent event) {
        validatePostEventEnvelope(
                event,
                event != null ? event.id() : null,
                event != null ? event.correlationId() : null,
                event != null ? event.occurredAt() : null,
                event != null ? event.postId() : null,
                event != null ? event.userId() : null
        );
    }

    protected final void validatePostDeletedEvent(PostDeletedEvent event) {
        validatePostEventEnvelope(
                event,
                event != null ? event.id() : null,
                event != null ? event.correlationId() : null,
                event != null ? event.occurredAt() : null,
                event != null ? event.postId() : null,
                event != null ? event.userId() : null
        );
    }

    private void validatePostEventEnvelope(
            Object event,
            UUID eventId,
            UUID correlationId,
            java.time.Instant occurredAt,
            UUID postId,
            UUID userId) {
        if (event == null) {
            throw new IllegalArgumentException("event must not be null");
        }
        if (eventId == null) {
            throw new IllegalArgumentException("event.id must not be null");
        }
        if (correlationId == null) {
            throw new IllegalArgumentException("event.correlationId must not be null");
        }
        if (occurredAt == null) {
            throw new IllegalArgumentException("event.occurredAt must not be null");
        }
        if (postId == null) {
            throw new IllegalArgumentException("event.postId must not be null");
        }
        if (userId == null) {
            throw new IllegalArgumentException("event.userId must not be null");
        }
    }

    protected final void validateUserRegisteredFromAuthEvent(UserRegisteredFromAuthEvent event) {
        validateAuthEventEnvelope(event, event != null ? event.userId() : null, event != null ? event.type() : null);
        if (event.details() == null) {
            throw new IllegalArgumentException("event.details must not be null");
        }
        if (!StringUtils.hasText(event.details().username())) {
            throw new IllegalArgumentException("event.details.username must not be blank");
        }
        if (!StringUtils.hasText(event.details().email())) {
            throw new IllegalArgumentException("event.details.email must not be blank");
        }
    }

    protected final void validateUserInfoFromAuthUpdatedEvent(UserInfoFromAuthUpdatedEvent event) {
        validateAuthEventEnvelope(event, event != null ? event.userId() : null, event != null ? event.type() : null);
        if (event.details() == null) {
            throw new IllegalArgumentException("event.details must not be null");
        }
        if (!StringUtils.hasText(event.details().username())) {
            throw new IllegalArgumentException("event.details.username must not be blank");
        }
        if (!StringUtils.hasText(event.details().email())) {
            throw new IllegalArgumentException("event.details.email must not be blank");
        }
    }

    protected final void validateUserDeletedFromAuthEvent(UserDeletedFromAuthEvent event) {
        validateAuthEventEnvelope(event, event != null ? event.userId() : null, event != null ? event.type() : null);
    }

    protected final void validateUserRegisteredEvent(UserRegisteredEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("event must not be null");
        }
        if (event.id() == null) {
            throw new IllegalArgumentException("event.id must not be null");
        }
        if (event.correlationId() == null) {
            throw new IllegalArgumentException("event.correlationId must not be null");
        }
        if (event.userId() == null) {
            throw new IllegalArgumentException("event.userId must not be null");
        }
    }

    protected final void validateUserDeletedEvent(UserDeletedEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("event must not be null");
        }
        if (event.id() == null) {
            throw new IllegalArgumentException("event.id must not be null");
        }
        if (event.correlationId() == null) {
            throw new IllegalArgumentException("event.correlationId must not be null");
        }
        if (event.userId() == null) {
            throw new IllegalArgumentException("event.userId must not be null");
        }
    }

    protected final void validateAuthEventEnvelope(Object event, String userId, String type) {
        if (event == null) {
            throw new IllegalArgumentException("event must not be null");
        }
        if (!StringUtils.hasText(type)) {
            throw new IllegalArgumentException("event.type must not be blank");
        }
        parseUuid(userId, "event.userId");
    }

    protected final UUID parseUuid(String rawUuid, String fieldName) {
        if (!StringUtils.hasText(rawUuid)) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        try {
            return UUID.fromString(rawUuid);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(fieldName + " must be a valid UUID", exception);
        }
    }

    protected final UUID buildDeterministicUuid(String namespace, String... components) {
        var seed = namespace + "|" + String.join("|", components);
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
    }
}
