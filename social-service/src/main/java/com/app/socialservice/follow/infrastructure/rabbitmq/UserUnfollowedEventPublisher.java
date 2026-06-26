package com.app.socialservice.follow.infrastructure.rabbitmq;

import java.nio.charset.StandardCharsets;

import com.app.socialservice.follow.infrastructure.events.UserUnfollowedEvent;
import com.app.socialservice.shared.infrastructure.entity.OutboxEvent;
import com.app.socialservice.shared.infrastructure.rabbitmq.config.RabbitMQProperties;
import com.app.socialservice.shared.infrastructure.rabbitmq.publisher.EventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class UserUnfollowedEventPublisher implements EventPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final RabbitMQProperties properties;

    @Override
    public boolean supports(String eventType) {
        return UserUnfollowedEvent.class.getSimpleName().equals(eventType);
    }

    @Override
    public void publish(OutboxEvent outboxEvent) {
        var exchange = properties.getExchange().getUser().getEvents();
        var routingKey = properties.getRk().getUser().getFollow().getDeleted();

        var message = MessageBuilder
                .withBody(outboxEvent.getPayload().getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .build();

        log.info("Sending unfollow message to exchange: {}, routingKey: {}, eventId: {}",
                exchange,
                routingKey,
                outboxEvent.getId());
        rabbitTemplate.send(exchange, routingKey, message);
    }
}
