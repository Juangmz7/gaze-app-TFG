package com.app.postcommandservice.like.infrastructure.rabbitmq;

import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import com.app.postcommandservice.like.application.commands.ValidatePostLikeCommand;
import com.app.postcommandservice.like.application.commands.ValidatePostUnlikeCommand;
import com.app.postcommandservice.like.application.repository.PostLikeCommandPublisher;
import com.app.postcommandservice.shared.infrastructure.entity.OutboxEvent;
import com.app.postcommandservice.shared.infrastructure.rabbitmq.config.RabbitMQProperties;
import com.app.postcommandservice.shared.infrastructure.outbox.OutboxDestination;
import com.app.postcommandservice.shared.infrastructure.rabbitmq.publisher.EventPublisher;

@Component
@RequiredArgsConstructor
public class ValidatePostLikeCommandPublisher implements PostLikeCommandPublisher, EventPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final RabbitMQProperties rabbitMQProperties;

    @Override
    public void publish(ValidatePostLikeCommand command) {
        rabbitTemplate.convertAndSend(
                rabbitMQProperties.getExchange().getPost().getCommands(),
                rabbitMQProperties.getRk().getPost().getLike().getValidate(),
                command
        );
    }

    @Override
    public boolean supports(String eventType) {
        return ValidatePostLikeCommand.class.getSimpleName().equals(eventType)
                || ValidatePostUnlikeCommand.class.getSimpleName().equals(eventType);
    }

    @Override
    public OutboxDestination destination(OutboxEvent outboxEvent) {
        var commands = rabbitMQProperties.getExchange().getPost().getCommands();

        if (ValidatePostLikeCommand.class.getSimpleName().equals(outboxEvent.getEventType())) {
            return new OutboxDestination(commands, rabbitMQProperties.getRk().getPost().getLike().getValidate());
        }

        if (ValidatePostUnlikeCommand.class.getSimpleName().equals(outboxEvent.getEventType())) {
            return new OutboxDestination(commands, rabbitMQProperties.getRk().getPost().getUnlike().getValidate());
        }

        throw new IllegalArgumentException("Unsupported outbox event type: " + outboxEvent.getEventType());
    }

    @Override
    public void publish(ValidatePostUnlikeCommand command) {
        rabbitTemplate.convertAndSend(
                rabbitMQProperties.getExchange().getPost().getCommands(),
                rabbitMQProperties.getRk().getPost().getUnlike().getValidate(),
                command
        );
    }
}
