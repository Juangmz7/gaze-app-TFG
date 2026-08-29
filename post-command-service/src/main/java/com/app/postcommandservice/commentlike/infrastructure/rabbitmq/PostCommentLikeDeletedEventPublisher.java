package com.app.postcommandservice.commentlike.infrastructure.rabbitmq;

import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import com.app.postcommandservice.commentlike.infrastructure.events.PostCommentLikeDeletedEvent;
import com.app.postcommandservice.shared.infrastructure.entity.OutboxEvent;
import com.app.postcommandservice.shared.infrastructure.mapper.JsonMapper;
import com.app.postcommandservice.shared.infrastructure.rabbitmq.config.RabbitMQProperties;
import com.app.postcommandservice.shared.infrastructure.rabbitmq.publisher.EventPublisher;

@Component
@RequiredArgsConstructor
public class PostCommentLikeDeletedEventPublisher implements EventPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final RabbitMQProperties rabbitMQProperties;
    private final JsonMapper jsonMapper;

    @Override
    public boolean supports(String eventType) {
        return PostCommentLikeDeletedEvent.class.getSimpleName().equals(eventType);
    }

    @Override
    public void publish(OutboxEvent outboxEvent) {
        var payload = jsonMapper.fromJson(outboxEvent.getPayload(), PostCommentLikeDeletedEvent.class);
        rabbitTemplate.convertAndSend(
                rabbitMQProperties.getExchange().getPost().getEvents(),
                rabbitMQProperties.getRk().getPost().getComment().getLike().getDeleted(),
                payload
        );
    }
}
