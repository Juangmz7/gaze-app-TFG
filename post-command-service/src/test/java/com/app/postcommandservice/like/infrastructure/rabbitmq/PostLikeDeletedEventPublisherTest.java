package com.app.postcommandservice.like.infrastructure.rabbitmq;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import com.app.postcommandservice.like.infrastructure.events.PostLikeDeletedEvent;
import com.app.postcommandservice.shared.infrastructure.entity.OutboxEvent;
import com.app.postcommandservice.shared.infrastructure.enums.EventStatus;
import com.app.postcommandservice.shared.infrastructure.mapper.JsonMapper;
import com.app.postcommandservice.shared.infrastructure.rabbitmq.config.RabbitMQProperties;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PostLikeDeletedEventPublisherTest {

    @Mock
    private RabbitTemplate rabbitTemplate;

    @Mock
    private JsonMapper jsonMapper;

    @Mock
    private RabbitMQProperties rabbitMQProperties;

    @Mock
    private RabbitMQProperties.Exchanges exchanges;

    @Mock
    private RabbitMQProperties.Exchanges.PostExchange postExchange;

    @Mock
    private RabbitMQProperties.RoutingKeys routingKeys;

    @Mock
    private RabbitMQProperties.RoutingKeys.PostRk postRk;

    @Mock
    private RabbitMQProperties.RoutingKeys.PostRk.LikeRk likeRk;

    @InjectMocks
    private PostLikeDeletedEventPublisher publisher;

    @Test
    void shouldPublishPostLikeDeletedEventToRabbitMqUsingConfiguredRoutingKey() {
        var payload = PostLikeDeletedEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(UUID.randomUUID())
                .occurredAt(Instant.now())
                .postId(UUID.randomUUID())
                .userId(UUID.randomUUID())
                .build();
        var outboxEvent = OutboxEvent.builder()
                .id(payload.id())
                .correlationId(payload.correlationId())
                .payload("{json}")
                .eventType(PostLikeDeletedEvent.class.getSimpleName())
                .status(EventStatus.PENDING)
                .createdAt(payload.occurredAt())
                .build();

        when(jsonMapper.fromJson("{json}", PostLikeDeletedEvent.class)).thenReturn(payload);
        when(rabbitMQProperties.getExchange()).thenReturn(exchanges);
        when(exchanges.getPost()).thenReturn(postExchange);
        when(postExchange.getEvents()).thenReturn("x.post.events");
        when(rabbitMQProperties.getRk()).thenReturn(routingKeys);
        when(routingKeys.getPost()).thenReturn(postRk);
        when(postRk.getLike()).thenReturn(likeRk);
        when(likeRk.getDeleted()).thenReturn("rk.post.like.deleted");

        publisher.publish(outboxEvent);

        verify(rabbitTemplate).convertAndSend("x.post.events", "rk.post.like.deleted", payload);
    }
}
