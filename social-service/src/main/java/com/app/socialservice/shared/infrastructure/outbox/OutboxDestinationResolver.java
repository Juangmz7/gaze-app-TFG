package com.app.socialservice.shared.infrastructure.outbox;

import java.util.List;

import jakarta.persistence.PrePersist;
import lombok.RequiredArgsConstructor;

import com.app.socialservice.shared.infrastructure.entity.OutboxEvent;
import com.app.socialservice.shared.infrastructure.exceptions.EventPublisherNotFound;
import com.app.socialservice.shared.infrastructure.rabbitmq.publisher.EventPublisher;

/**
 * JPA entity listener (instantiated through Spring's bean container) that
 * stores exchange and routing key on every new outbox row. It runs inside the
 * business transaction, so an event type without a publisher fails that
 * transaction instead of leaving an unpublishable row behind.
 */
@RequiredArgsConstructor
public class OutboxDestinationResolver {

    private final List<EventPublisher> publishers;

    @PrePersist
    void assignDestination(OutboxEvent outboxEvent) {
        if (outboxEvent.getExchange() != null && outboxEvent.getRoutingKey() != null) {
            return;
        }

        var destination = publishers.stream()
                .filter(p -> p.supports(outboxEvent.getEventType()))
                .findFirst()
                .orElseThrow(() -> new EventPublisherNotFound("No publisher for: " + outboxEvent.getEventType()))
                .destination(outboxEvent);

        outboxEvent.setExchange(destination.exchange());
        outboxEvent.setRoutingKey(destination.routingKey());
    }
}
