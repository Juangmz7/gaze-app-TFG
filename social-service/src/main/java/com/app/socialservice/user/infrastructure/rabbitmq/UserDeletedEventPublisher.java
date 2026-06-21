package com.app.socialservice.user.infrastructure.rabbitmq;

import com.app.socialservice.shared.infrastructure.rabbitmq.config.RabbitMQProperties;
import com.app.socialservice.shared.infrastructure.entity.OutboxEvent;
import com.app.socialservice.shared.infrastructure.rabbitmq.publisher.EventPublisher;
import com.app.socialservice.user.infrastructure.events.UserDeletedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Slf4j
@Component
@RequiredArgsConstructor
public class UserDeletedEventPublisher implements EventPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final RabbitMQProperties properties;

    public boolean supports(String eventType) {
        return UserDeletedEvent.class.getSimpleName().equals(eventType);
    }

    public void publish(OutboxEvent outboxEvent) {
        var exchange = properties.getExchange().getUser().getEvents();
        var routingKey = properties.getRk().getUser().getDeleted();

        var message = MessageBuilder
                .withBody(outboxEvent.getPayload().getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .build();

        log.info("Sending message to exchange: {}, routingKey: {}, eventId: {}", exchange, routingKey, outboxEvent.getId());
        rabbitTemplate.send(exchange, routingKey, message);
    }
}
