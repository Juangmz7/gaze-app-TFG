package com.app.postcommandservice.comment.infrastructure.rabbitmq;

import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import com.app.postcommandservice.comment.infrastructure.events.CommentCreatedEvent;
import com.app.postcommandservice.shared.infrastructure.entity.OutboxEvent;
import com.app.postcommandservice.shared.infrastructure.mapper.JsonMapper;
import com.app.postcommandservice.shared.infrastructure.rabbitmq.config.RabbitMQProperties;
import com.app.postcommandservice.shared.infrastructure.rabbitmq.publisher.EventPublisher;

@Component
@RequiredArgsConstructor
public class CommentCreatedEventPublisher implements EventPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final RabbitMQProperties rabbitMQProperties;
    private final JsonMapper jsonMapper;

    @Override
    public boolean supports(String eventType) {
        return CommentCreatedEvent.class.getSimpleName().equals(eventType);
    }

    @Override
    public void publish(OutboxEvent outboxEvent) {
        var event = jsonMapper.fromJson(outboxEvent.getPayload(), CommentCreatedEvent.class);
        rabbitTemplate.convertAndSend(
                rabbitMQProperties.getExchange().getPost().getEvents(),
                rabbitMQProperties.getRk().getPost().getComment().getCreated(),
                event
        );
    }
}
