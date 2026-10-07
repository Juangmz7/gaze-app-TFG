package com.app.postcommandservice.like.infrastructure.rabbitmq;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import com.app.postcommandservice.like.infrastructure.events.PostLikeCreatedEvent;
import com.app.postcommandservice.shared.infrastructure.entity.OutboxEvent;
import com.app.postcommandservice.shared.infrastructure.rabbitmq.config.RabbitMQProperties;
import com.app.postcommandservice.shared.infrastructure.outbox.OutboxDestination;
import com.app.postcommandservice.shared.infrastructure.rabbitmq.publisher.EventPublisher;

@Component
@RequiredArgsConstructor
public class PostLikeCreatedEventPublisher implements EventPublisher {

    private final RabbitMQProperties rabbitMQProperties;

    @Override
    public boolean supports(String eventType) {
        return PostLikeCreatedEvent.class.getSimpleName().equals(eventType);
    }

    @Override
    public OutboxDestination destination(OutboxEvent outboxEvent) {
        return new OutboxDestination(
                rabbitMQProperties.getExchange().getPost().getEvents(),
                rabbitMQProperties.getRk().getPost().getLike().getCreated()
        );
    }
}
