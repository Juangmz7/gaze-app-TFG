package com.app.postcommandservice.shared.infrastructure.rabbitmq.publisher;

import com.app.postcommandservice.shared.infrastructure.entity.OutboxEvent;
import com.app.postcommandservice.shared.infrastructure.outbox.OutboxDestination;

/**
 * Declares where an outbox event type is published. The actual send (with
 * publisher confirms) is done by the generic {@code OutboxRelay}.
 */
public interface EventPublisher {
    boolean supports(String eventType);
    OutboxDestination destination(OutboxEvent outboxEvent);
}
