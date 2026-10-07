package com.app.socialservice.user.infrastructure.rabbitmq;

import com.app.socialservice.shared.infrastructure.rabbitmq.config.RabbitMQProperties;
import com.app.socialservice.shared.infrastructure.entity.OutboxEvent;
import com.app.socialservice.shared.infrastructure.outbox.OutboxDestination;
import com.app.socialservice.shared.infrastructure.rabbitmq.publisher.EventPublisher;
import com.app.socialservice.user.infrastructure.events.UserRegisteredEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class UserRegisteredEventPublisher implements EventPublisher {

    private final RabbitMQProperties properties;

    @Override
    public boolean supports(String eventType) {
        return UserRegisteredEvent.class.getSimpleName().equals(eventType);
    }

    @Override
    public OutboxDestination destination(OutboxEvent outboxEvent) {
        return new OutboxDestination(
                properties.getExchange().getUser().getEvents(),
                properties.getRk().getUser().getRegister().getCreated()
        );
    }
}
