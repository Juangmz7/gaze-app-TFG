package com.app.postcommandservice.commentlike.infrastructure.rabbitmq;

import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import com.app.postcommandservice.commentlike.application.commands.ValidateCommentLikeCommand;
import com.app.postcommandservice.commentlike.application.commands.ValidateCommentUnlikeCommand;
import com.app.postcommandservice.commentlike.application.repository.PostCommentLikeCommandPublisher;
import com.app.postcommandservice.shared.infrastructure.entity.OutboxEvent;
import com.app.postcommandservice.shared.infrastructure.mapper.JsonMapper;
import com.app.postcommandservice.shared.infrastructure.rabbitmq.config.RabbitMQProperties;
import com.app.postcommandservice.shared.infrastructure.rabbitmq.publisher.EventPublisher;

@Component
@RequiredArgsConstructor
public class ValidateCommentLikeCommandPublisher implements PostCommentLikeCommandPublisher, EventPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final RabbitMQProperties rabbitMQProperties;
    private final JsonMapper jsonMapper;

    @Override
    public void publish(ValidateCommentLikeCommand command) {
        rabbitTemplate.convertAndSend(
                rabbitMQProperties.getExchange().getPost().getCommands(),
                rabbitMQProperties.getRk().getPost().getComment().getLike().getValidate(),
                command
        );
    }

    @Override
    public boolean supports(String eventType) {
        return ValidateCommentLikeCommand.class.getSimpleName().equals(eventType)
                || ValidateCommentUnlikeCommand.class.getSimpleName().equals(eventType);
    }

    @Override
    public void publish(OutboxEvent outboxEvent) {
        if (ValidateCommentLikeCommand.class.getSimpleName().equals(outboxEvent.getEventType())) {
            publish(jsonMapper.fromJson(outboxEvent.getPayload(), ValidateCommentLikeCommand.class));
            return;
        }

        if (ValidateCommentUnlikeCommand.class.getSimpleName().equals(outboxEvent.getEventType())) {
            publish(jsonMapper.fromJson(outboxEvent.getPayload(), ValidateCommentUnlikeCommand.class));
            return;
        }

        throw new IllegalArgumentException("Unsupported outbox event type: " + outboxEvent.getEventType());
    }

    @Override
    public void publish(ValidateCommentUnlikeCommand command) {
        rabbitTemplate.convertAndSend(
                rabbitMQProperties.getExchange().getPost().getCommands(),
                rabbitMQProperties.getRk().getPost().getComment().getLike().getDeleted(),
                command
        );
    }
}
