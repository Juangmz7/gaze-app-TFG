package com.app.socialservice.follow.infrastructure.rabbitmq;

import com.app.socialservice.follow.infrastructure.events.UserUnfollowedEvent;
import com.app.socialservice.shared.infrastructure.entity.OutboxEvent;
import com.app.socialservice.shared.infrastructure.rabbitmq.config.RabbitMQProperties;
import com.app.socialservice.shared.infrastructure.outbox.OutboxDestination;
import com.app.socialservice.shared.infrastructure.rabbitmq.publisher.EventPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class UserUnfollowedEventPublisher implements EventPublisher {

    private final RabbitMQProperties properties;

    @Override
    public boolean supports(String eventType) {
        return UserUnfollowedEvent.class.getSimpleName().equals(eventType);
    }

    @Override
    public OutboxDestination destination(OutboxEvent outboxEvent) {
        return new OutboxDestination(
                properties.getExchange().getUser().getEvents(),
                properties.getRk().getUser().getFollow().getDeleted()
        );
    }
}
