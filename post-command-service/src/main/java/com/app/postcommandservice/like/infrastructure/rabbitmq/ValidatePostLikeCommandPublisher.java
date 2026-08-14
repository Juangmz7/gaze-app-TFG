package com.app.postcommandservice.like.infrastructure.rabbitmq;

import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import com.app.postcommandservice.like.application.commands.ValidatePostLikeCommand;
import com.app.postcommandservice.like.application.repository.PostLikeCommandPublisher;
import com.app.postcommandservice.shared.infrastructure.rabbitmq.config.RabbitMQProperties;

@Component
@RequiredArgsConstructor
public class ValidatePostLikeCommandPublisher implements PostLikeCommandPublisher {

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
}
