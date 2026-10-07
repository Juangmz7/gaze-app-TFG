package com.app.postcommandservice.view.infrastructure.rabbitmq;

import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import com.app.postcommandservice.shared.infrastructure.entity.OutboxEvent;
import com.app.postcommandservice.shared.infrastructure.rabbitmq.config.RabbitMQProperties;
import com.app.postcommandservice.shared.infrastructure.outbox.OutboxDestination;
import com.app.postcommandservice.shared.infrastructure.rabbitmq.publisher.EventPublisher;
import com.app.postcommandservice.view.application.commands.ProcessPostViewCommand;
import com.app.postcommandservice.view.application.repository.PostViewCommandPublisher;

@Component
@RequiredArgsConstructor
public class ProcessPostViewCommandPublisher implements PostViewCommandPublisher, EventPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final RabbitMQProperties rabbitMQProperties;

    @Override
    public boolean supports(String eventType) {
        return ProcessPostViewCommand.class.getSimpleName().equals(eventType);
    }

    @Override
    public OutboxDestination destination(OutboxEvent outboxEvent) {
        return new OutboxDestination(
                rabbitMQProperties.getExchange().getPost().getCommands(),
                rabbitMQProperties.getRk().getPost().getView().getProcess()
        );
    }

    @Override
    public void publish(ProcessPostViewCommand command) {
        rabbitTemplate.convertAndSend(
                rabbitMQProperties.getExchange().getPost().getCommands(),
                rabbitMQProperties.getRk().getPost().getView().getProcess(),
                command
        );
    }
}
