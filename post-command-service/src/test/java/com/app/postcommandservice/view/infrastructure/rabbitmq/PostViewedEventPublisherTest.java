package com.app.postcommandservice.view.infrastructure.rabbitmq;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import com.app.postcommandservice.shared.infrastructure.entity.OutboxEvent;
import com.app.postcommandservice.shared.infrastructure.mapper.JsonMapper;
import com.app.postcommandservice.shared.infrastructure.rabbitmq.config.RabbitMQProperties;
import com.app.postcommandservice.view.domain.model.PostViewExitReason;
import com.app.postcommandservice.view.domain.model.PostViewSource;
import com.app.postcommandservice.view.infrastructure.events.PostViewedEvent;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PostViewedEventPublisherTest {

    @Mock
    private RabbitTemplate rabbitTemplate;

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
    private JsonMapper jsonMapper;

    @InjectMocks
    private PostViewedEventPublisher publisher;

    @Test
    void shouldPublishPostViewedEventUsingConfiguredExchangeAndRoutingKey() {
        var event = PostViewedEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(UUID.randomUUID())
                .occurredAt(Instant.now())
                .viewId(UUID.randomUUID())
                .postId(UUID.randomUUID())
                .userId(UUID.randomUUID())
                .source(PostViewSource.SEARCH)
                .feedPosition(8)
                .durationMs(2000)
                .timeWatchedMs(1600)
                .completionPercent(80)
                .exitReason(PostViewExitReason.VIDEO_COMPLETED)
                .serverTimestamp(Instant.now())
                .replayCount(2)
                .build();
        var outboxEvent = OutboxEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(UUID.randomUUID())
                .payload("{\"payload\":true}")
                .build();

        when(jsonMapper.fromJson(outboxEvent.getPayload(), PostViewedEvent.class)).thenReturn(event);
        when(rabbitMQProperties.getExchange()).thenReturn(exchanges);
        when(exchanges.getPost()).thenReturn(postExchange);
        when(postExchange.getEvents()).thenReturn("x.post.events");
        when(rabbitMQProperties.getRk()).thenReturn(routingKeys);
        when(routingKeys.getPost()).thenReturn(postRk);
        when(postRk.getViewed()).thenReturn("rk.post.viewed");

        publisher.publish(outboxEvent);

        verify(rabbitTemplate).convertAndSend("x.post.events", "rk.post.viewed", event);
    }
}
