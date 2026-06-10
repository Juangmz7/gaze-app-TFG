package com.app.socialservice.shared.infrastructure.rabbitmq;

import com.app.socialservice.shared.infrastructure.entity.OutboxEvent;


public interface EventPublisher {
    boolean supports(String eventType);
    void publish(OutboxEvent outboxEvent);
}
