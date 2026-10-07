package com.app.postcommandservice.comment.infrastructure.rabbitmq;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import com.app.postcommandservice.comment.infrastructure.events.CommentCreatedEvent;
import com.app.postcommandservice.shared.infrastructure.entity.OutboxEvent;
import com.app.postcommandservice.shared.infrastructure.rabbitmq.config.RabbitMQProperties;
import com.app.postcommandservice.shared.infrastructure.outbox.OutboxDestination;
import com.app.postcommandservice.shared.infrastructure.rabbitmq.publisher.EventPublisher;

@Component
@RequiredArgsConstructor
public class CommentCreatedEventPublisher implements EventPublisher {

    private final RabbitMQProperties rabbitMQProperties;

    @Override
    public boolean supports(String eventType) {
        return CommentCreatedEvent.class.getSimpleName().equals(eventType);
    }

    @Override
    public OutboxDestination destination(OutboxEvent outboxEvent) {
        return new OutboxDestination(
                rabbitMQProperties.getExchange().getPost().getEvents(),
                rabbitMQProperties.getRk().getPost().getComment().getCreated()
        );
    }
}
