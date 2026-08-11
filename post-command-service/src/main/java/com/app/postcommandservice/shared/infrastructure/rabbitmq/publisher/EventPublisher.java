package com.app.postcommandservice.shared.infrastructure.rabbitmq.publisher;

import com.app.postcommandservice.shared.infrastructure.entity.OutboxEvent;

public interface EventPublisher {
    boolean supports(String eventType);
    void publish(OutboxEvent outboxEvent);
}
