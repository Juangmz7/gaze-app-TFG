package com.app.postcommandservice.view.infrastructure.rabbitmq;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import com.app.postcommandservice.shared.infrastructure.entity.OutboxEvent;
import com.app.postcommandservice.shared.infrastructure.rabbitmq.config.RabbitMQProperties;
import com.app.postcommandservice.shared.infrastructure.outbox.OutboxDestination;
import com.app.postcommandservice.shared.infrastructure.rabbitmq.publisher.EventPublisher;
import com.app.postcommandservice.view.infrastructure.events.PostViewedEvent;

@Component
@RequiredArgsConstructor
public class PostViewedEventPublisher implements EventPublisher {

    private final RabbitMQProperties rabbitMQProperties;

    @Override
    public boolean supports(String eventType) {
        return PostViewedEvent.class.getSimpleName().equals(eventType);
    }

    @Override
    public OutboxDestination destination(OutboxEvent outboxEvent) {
        return new OutboxDestination(
                rabbitMQProperties.getExchange().getPost().getEvents(),
                rabbitMQProperties.getRk().getPost().getViewed()
        );
    }
}
