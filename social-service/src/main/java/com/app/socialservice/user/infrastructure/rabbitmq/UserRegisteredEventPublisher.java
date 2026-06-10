package com.app.socialservice.user.infrastructure.rabbitmq;

import com.app.socialservice.shared.infrastructure.rabbitmq.config.RabbitMQProperties;
import com.app.socialservice.shared.infrastructure.entity.OutboxEvent;
import com.app.socialservice.shared.infrastructure.rabbitmq.publisher.EventPublisher;
import com.app.socialservice.user.infrastructure.events.UserRegisteredEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class UserRegisteredEventPublisher implements EventPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final RabbitMQProperties properties;

    public boolean supports(String eventType) {
        return UserRegisteredEvent.class.getSimpleName().equals(eventType);
    }

    public void publish(OutboxEvent outboxEvent) {
        var exchange = properties.getExchange().getUser().getEvents();
        var routingKey = properties.getRk().getUser().getRegister().getCreated();

        log.info("Sending message to exchange: {}, routingKey: {}", exchange, routingKey);

        rabbitTemplate.convertAndSend(
                exchange,
                routingKey,
                outboxEvent.getPayload()
        );
    }
}
