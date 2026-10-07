package com.app.postcommandservice.share.infrastructure.rabbitmq;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import com.app.postcommandservice.share.infrastructure.events.PostShareDeletedEvent;
import com.app.postcommandservice.shared.infrastructure.entity.OutboxEvent;
import com.app.postcommandservice.shared.infrastructure.outbox.OutboxDestination;
import com.app.postcommandservice.shared.infrastructure.enums.EventStatus;
import com.app.postcommandservice.shared.infrastructure.mapper.JsonMapper;
import com.app.postcommandservice.shared.infrastructure.rabbitmq.config.RabbitMQProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PostShareDeletedEventPublisherTest {



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
    private RabbitMQProperties.RoutingKeys.PostRk.ShareRk shareRk;

    @InjectMocks
    private PostShareDeletedEventPublisher publisher;

    @Test
    void shouldPublishPostShareDeletedEventToRabbitMqUsingConfiguredRoutingKey() {
        var payload = PostShareDeletedEvent.builder()
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
                .eventType(PostShareDeletedEvent.class.getSimpleName())
                .status(EventStatus.PENDING)
                .createdAt(payload.occurredAt())
                .build();
        when(rabbitMQProperties.getExchange()).thenReturn(exchanges);
        when(exchanges.getPost()).thenReturn(postExchange);
        when(postExchange.getEvents()).thenReturn("x.post.events");
        when(rabbitMQProperties.getRk()).thenReturn(routingKeys);
        when(routingKeys.getPost()).thenReturn(postRk);
        when(postRk.getShare()).thenReturn(shareRk);
        when(shareRk.getDeleted()).thenReturn("rk.post.share.deleted");

        assertThat(publisher.destination(outboxEvent))
                .isEqualTo(new OutboxDestination("x.post.events", "rk.post.share.deleted"));
    }
}
