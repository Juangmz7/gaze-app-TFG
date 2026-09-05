package com.app.postcommandservice.comment.infrastructure.rabbitmq;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import com.app.postcommandservice.comment.infrastructure.events.CommentUpdatedEvent;
import com.app.postcommandservice.shared.infrastructure.entity.OutboxEvent;
import com.app.postcommandservice.shared.infrastructure.enums.EventStatus;
import com.app.postcommandservice.shared.infrastructure.mapper.JsonMapper;
import com.app.postcommandservice.shared.infrastructure.rabbitmq.config.RabbitMQProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CommentUpdatedEventPublisherTest {

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
    private RabbitMQProperties.RoutingKeys.PostRk.CommentRk commentRk;

    @InjectMocks
    private CommentUpdatedEventPublisher publisher;

    @Test
    void shouldSupportOnlyCommentUpdatedEventOutboxEventType() {
        assertThat(publisher.supports(CommentUpdatedEvent.class.getSimpleName())).isTrue();
        assertThat(publisher.supports("CommentCreatedEvent")).isFalse();
    }

    @Test
    void shouldPublishCommentUpdatedEventToRabbitMqUsingConfiguredRoutingKey() {
        var now = Instant.now();
        var payload = CommentUpdatedEvent.builder()
                .commentId(UUID.randomUUID())
                .postId(UUID.randomUUID())
                .userId(UUID.randomUUID())
                .content("updated")
                .replyTo(UUID.randomUUID())
                .createdAt(now.minusSeconds(30))
                .updatedAt(now)
                .build();
        var outboxEvent = OutboxEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(UUID.randomUUID())
                .payload("{json}")
                .eventType(CommentUpdatedEvent.class.getSimpleName())
                .status(EventStatus.PENDING)
                .createdAt(now)
                .build();

        when(jsonMapper.fromJson("{json}", CommentUpdatedEvent.class)).thenReturn(payload);
        when(rabbitMQProperties.getExchange()).thenReturn(exchanges);
        when(exchanges.getPost()).thenReturn(postExchange);
        when(postExchange.getEvents()).thenReturn("x.post.events");
        when(rabbitMQProperties.getRk()).thenReturn(routingKeys);
        when(routingKeys.getPost()).thenReturn(postRk);
        when(postRk.getComment()).thenReturn(commentRk);
        when(commentRk.getUpdated()).thenReturn("rk.post.comment.updated");

        publisher.publish(outboxEvent);

        verify(rabbitTemplate).convertAndSend("x.post.events", "rk.post.comment.updated", payload);
    }
}
