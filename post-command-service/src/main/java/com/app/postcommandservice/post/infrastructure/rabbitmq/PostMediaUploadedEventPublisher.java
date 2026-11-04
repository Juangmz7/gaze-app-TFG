package com.app.postcommandservice.post.infrastructure.rabbitmq;

import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import com.app.postcommandservice.post.infrastructure.events.PostMediaUploadedEvent;
import com.app.postcommandservice.shared.infrastructure.rabbitmq.config.RabbitMQProperties;

/**
 * Publishes {@link PostMediaUploadedEvent} directly to RabbitMQ, bypassing the
 * transactional outbox: the confirm-media-upload endpoint (task 35) performs no
 * database write to anchor an outbox row to, and the HTTP response must only be
 * {@code 202 Accepted} once the broker has actually accepted the message. Any
 * publish failure propagates to the caller so the controller never returns 202.
 */
@Component
@RequiredArgsConstructor
public class PostMediaUploadedEventPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final RabbitMQProperties rabbitMQProperties;

    public void publish(PostMediaUploadedEvent event) {
        rabbitTemplate.convertAndSend(
                rabbitMQProperties.getExchange().getPost().getEvents(),
                rabbitMQProperties.getRk().getPost().getMedia().getUploaded(),
                event
        );
    }
}
