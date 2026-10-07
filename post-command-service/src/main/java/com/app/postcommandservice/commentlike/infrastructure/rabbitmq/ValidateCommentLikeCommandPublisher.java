package com.app.postcommandservice.commentlike.infrastructure.rabbitmq;

import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import com.app.postcommandservice.commentlike.application.commands.ValidateCommentLikeCommand;
import com.app.postcommandservice.commentlike.application.commands.ValidateCommentUnlikeCommand;
import com.app.postcommandservice.commentlike.application.repository.PostCommentLikeCommandPublisher;
import com.app.postcommandservice.shared.infrastructure.entity.OutboxEvent;
import com.app.postcommandservice.shared.infrastructure.rabbitmq.config.RabbitMQProperties;
import com.app.postcommandservice.shared.infrastructure.outbox.OutboxDestination;
import com.app.postcommandservice.shared.infrastructure.rabbitmq.publisher.EventPublisher;

@Component
@RequiredArgsConstructor
public class ValidateCommentLikeCommandPublisher implements PostCommentLikeCommandPublisher, EventPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final RabbitMQProperties rabbitMQProperties;

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
    public OutboxDestination destination(OutboxEvent outboxEvent) {
        var commands = rabbitMQProperties.getExchange().getPost().getCommands();
        var commentLikeRk = rabbitMQProperties.getRk().getPost().getComment().getLike();

        if (ValidateCommentLikeCommand.class.getSimpleName().equals(outboxEvent.getEventType())) {
            return new OutboxDestination(commands, commentLikeRk.getValidate());
        }

        if (ValidateCommentUnlikeCommand.class.getSimpleName().equals(outboxEvent.getEventType())) {
            return new OutboxDestination(commands, commentLikeRk.getDeleted());
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
