package com.app.postcommandservice.share.infrastructure.rabbitmq;

import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import com.app.postcommandservice.share.application.commands.CreatePostShareCommand;
import com.app.postcommandservice.share.application.repository.PostShareCommandPublisher;
import com.app.postcommandservice.shared.infrastructure.entity.OutboxEvent;
import com.app.postcommandservice.shared.infrastructure.mapper.JsonMapper;
import com.app.postcommandservice.shared.infrastructure.rabbitmq.config.RabbitMQProperties;
import com.app.postcommandservice.shared.infrastructure.rabbitmq.publisher.EventPublisher;

@Component
@RequiredArgsConstructor
public class CreatePostShareCommandPublisher implements PostShareCommandPublisher, EventPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final RabbitMQProperties rabbitMQProperties;
    private final JsonMapper jsonMapper;

    @Override
    public void publish(CreatePostShareCommand command) {
        rabbitTemplate.convertAndSend(
                rabbitMQProperties.getExchange().getPost().getCommands(),
                rabbitMQProperties.getRk().getPost().getShare().getCreate().getValidate(),
                command
        );
    }

    @Override
    public boolean supports(String eventType) {
        return CreatePostShareCommand.class.getSimpleName().equals(eventType);
    }

    @Override
    public void publish(OutboxEvent outboxEvent) {
        publish(jsonMapper.fromJson(outboxEvent.getPayload(), CreatePostShareCommand.class));
    }
}
