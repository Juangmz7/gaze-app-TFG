package com.app.postcommandservice.collab.infrastructure.rabbitmq;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import com.app.postcommandservice.collab.infrastructure.events.CollabOpenedEvent;
import com.app.postcommandservice.shared.infrastructure.entity.OutboxEvent;
import com.app.postcommandservice.shared.infrastructure.mapper.JsonMapper;
import com.app.postcommandservice.shared.infrastructure.rabbitmq.config.RabbitMQProperties;
import com.app.postcommandservice.shared.infrastructure.outbox.OutboxDestination;
import com.app.postcommandservice.shared.infrastructure.rabbitmq.publisher.EventPublisher;

@Component
@RequiredArgsConstructor
public class CollabOpenedEventPublisher implements EventPublisher {

    private final RabbitMQProperties rabbitMQProperties;
    private final JsonMapper jsonMapper;

    @Override
    public boolean supports(String eventType) {
        return CollabOpenedEvent.class.getSimpleName().equals(eventType);
    }

    @Override
    public OutboxDestination destination(OutboxEvent outboxEvent) {
        var event = jsonMapper.fromJson(outboxEvent.getPayload(), CollabOpenedEvent.class);
        var routingKey = event.withPostCreated()
                ? rabbitMQProperties.getRk().getPost().getCollab().getOpened().getPostCreated()
                : rabbitMQProperties.getRk().getPost().getCollab().getOpened().getExistingPost();

        return new OutboxDestination(rabbitMQProperties.getExchange().getPost().getEvents(), routingKey);
    }
}
