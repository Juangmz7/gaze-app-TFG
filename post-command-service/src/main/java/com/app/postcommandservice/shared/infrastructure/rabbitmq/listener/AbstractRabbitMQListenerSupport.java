package com.app.postcommandservice.shared.infrastructure.rabbitmq.listener;

import java.time.Instant;
import java.util.UUID;

import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.util.StringUtils;

import com.app.postcommandservice.post.infrastructure.events.UserDeletedEvent;
import com.app.postcommandservice.post.infrastructure.events.UserRegisteredEvent;
import com.app.postcommandservice.post.infrastructure.events.UserUpdatedEvent;
import com.app.postcommandservice.shared.infrastructure.rabbitmq.config.RabbitMQProperties;
import com.app.postcommandservice.shared.infrastructure.repository.ProcessedEventsRepository;

public abstract class AbstractRabbitMQListenerSupport {

    private final ProcessedEventsRepository processedEventsRepository;
    protected final RabbitMQProperties rabbitMQProperties;

    protected AbstractRabbitMQListenerSupport(
            ProcessedEventsRepository processedEventsRepository,
            RabbitMQProperties rabbitMQProperties) {
        this.processedEventsRepository = processedEventsRepository;
        this.rabbitMQProperties = rabbitMQProperties;
    }

    protected final boolean isEventAlreadyProcessed(UUID eventId, UUID correlationId) {
        return processedEventsRepository.existsById(eventId);
    }

    protected final void setEventAsProcessed(UUID eventId, UUID correlationId, String eventType) {
        processedEventsRepository.insertIfAbsent(eventId, correlationId, eventType);
    }

    protected final AmqpRejectAndDontRequeueException rejectToDlq(RuntimeException exception) {
        return new AmqpRejectAndDontRequeueException(exception.getMessage(), exception);
    }

    protected final void validateUserRegisteredEvent(UserRegisteredEvent event) {
        validateEventEnvelope(
                event,
                event != null ? event.id() : null,
                event != null ? event.correlationId() : null,
                event != null ? event.occurredAt() : null
        );
        if (event.userId() == null) {
            throw new IllegalArgumentException("event.userId must not be null");
        }
        if (!StringUtils.hasText(event.username())) {
            throw new IllegalArgumentException("event.username must not be blank");
        }
    }

    protected final void validateUserUpdatedEvent(UserUpdatedEvent event) {
        validateEventEnvelope(
                event,
                event != null ? event.id() : null,
                event != null ? event.correlationId() : null,
                event != null ? event.occurredAt() : null
        );
        if (event.userId() == null) {
            throw new IllegalArgumentException("event.userId must not be null");
        }
        if (!StringUtils.hasText(event.username())) {
            throw new IllegalArgumentException("event.username must not be blank");
        }
    }

    protected final void validateUserDeletedEvent(UserDeletedEvent event) {
        validateEventEnvelope(
                event,
                event != null ? event.id() : null,
                event != null ? event.correlationId() : null,
                event != null ? event.occurredAt() : null
        );
        if (event.userId() == null) {
            throw new IllegalArgumentException("event.userId must not be null");
        }
    }

    protected final void validateUserBlockedEvent(
            UUID eventId,
            UUID correlationId,
            Instant occurredAt,
            UUID blockerUserId,
            UUID blockedUserId) {
        validateEventEnvelope(new Object(), eventId, correlationId, occurredAt);
        if (blockerUserId == null) {
            throw new IllegalArgumentException("event.blockerUserId must not be null");
        }
        if (blockedUserId == null) {
            throw new IllegalArgumentException("event.blockedUserId must not be null");
        }
        if (blockerUserId.equals(blockedUserId)) {
            throw new IllegalArgumentException("event blocker and blocked users must be different");
        }
    }

    private void validateEventEnvelope(Object event, UUID eventId, UUID correlationId, Instant occurredAt) {
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
    }

}
