package com.app.postcommandservice.commentlike.infrastructure.rabbitmq;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import com.app.postcommandservice.commentlike.domain.model.CommentLikeSource;
import com.app.postcommandservice.commentlike.infrastructure.events.PostCommentLikeCreatedEvent;
import com.app.postcommandservice.shared.infrastructure.entity.OutboxEvent;
import com.app.postcommandservice.shared.infrastructure.mapper.JsonMapper;
import com.app.postcommandservice.shared.infrastructure.rabbitmq.config.RabbitMQProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PostCommentLikeCreatedEventPublisherTest {

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

    @Mock
    private RabbitMQProperties.RoutingKeys.PostRk.CommentRk.LikeRk likeRk;

    @InjectMocks
    private PostCommentLikeCreatedEventPublisher publisher;

    @Test
    void shouldSupportOnlyCommentLikeCreatedEventOutboxEventType() {
        assertThat(publisher.supports(PostCommentLikeCreatedEvent.class.getSimpleName())).isTrue();
        assertThat(publisher.supports("PostCreatedEvent")).isFalse();
    }

    @Test
    void shouldPublishCommentLikeCreatedEventToRabbitMqUsingConfiguredRoutingKey() {
        var payload = PostCommentLikeCreatedEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(UUID.randomUUID())
                .occurredAt(Instant.now())
                .postId(UUID.randomUUID())
                .commentId(UUID.randomUUID())
                .userId(UUID.randomUUID())
                .source(CommentLikeSource.SEARCH)
                .feedPosition(4)
                .createdAt(Instant.now())
                .build();
        var outboxEvent = OutboxEvent.builder()
                .id(payload.id())
                .correlationId(payload.correlationId())
                .payload("{json}")
                .eventType(PostCommentLikeCreatedEvent.class.getSimpleName())
                .build();

        when(jsonMapper.fromJson("{json}", PostCommentLikeCreatedEvent.class)).thenReturn(payload);
        when(rabbitMQProperties.getExchange()).thenReturn(exchanges);
        when(exchanges.getPost()).thenReturn(postExchange);
        when(postExchange.getEvents()).thenReturn("x.post.events");
        when(rabbitMQProperties.getRk()).thenReturn(routingKeys);
        when(routingKeys.getPost()).thenReturn(postRk);
        when(postRk.getComment()).thenReturn(commentRk);
        when(commentRk.getLike()).thenReturn(likeRk);
        when(likeRk.getCreated()).thenReturn("rk.post.comment.like.created");

        publisher.publish(outboxEvent);

        verify(rabbitTemplate).convertAndSend("x.post.events", "rk.post.comment.like.created", payload);
    }
}
