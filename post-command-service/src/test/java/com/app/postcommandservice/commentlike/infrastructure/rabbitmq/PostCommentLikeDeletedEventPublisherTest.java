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
import com.app.postcommandservice.commentlike.infrastructure.events.PostCommentLikeDeletedEvent;
import com.app.postcommandservice.shared.infrastructure.entity.OutboxEvent;
import com.app.postcommandservice.shared.infrastructure.mapper.JsonMapper;
import com.app.postcommandservice.shared.infrastructure.rabbitmq.config.RabbitMQProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PostCommentLikeDeletedEventPublisherTest {

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
    private PostCommentLikeDeletedEventPublisher publisher;

    @Test
    void shouldSupportOnlyCommentLikeDeletedEventOutboxEventType() {
        assertThat(publisher.supports(PostCommentLikeDeletedEvent.class.getSimpleName())).isTrue();
        assertThat(publisher.supports("PostCreatedEvent")).isFalse();
    }

    @Test
    void shouldPublishCommentLikeDeletedEventToRabbitMqUsingConfiguredRoutingKey() {
        var payload = PostCommentLikeDeletedEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(UUID.randomUUID())
                .occurredAt(Instant.now())
                .commentId(UUID.randomUUID())
                .userId(UUID.randomUUID())
                .source(CommentLikeSource.USER_PROFILE)
                .feedPosition(4)
                .build();
        var outboxEvent = OutboxEvent.builder()
                .id(payload.id())
                .correlationId(payload.correlationId())
                .payload("{json}")
                .eventType(PostCommentLikeDeletedEvent.class.getSimpleName())
                .build();

        when(jsonMapper.fromJson("{json}", PostCommentLikeDeletedEvent.class)).thenReturn(payload);
        when(rabbitMQProperties.getExchange()).thenReturn(exchanges);
        when(exchanges.getPost()).thenReturn(postExchange);
        when(postExchange.getEvents()).thenReturn("x.post.events");
        when(rabbitMQProperties.getRk()).thenReturn(routingKeys);
        when(routingKeys.getPost()).thenReturn(postRk);
        when(postRk.getComment()).thenReturn(commentRk);
        when(commentRk.getLike()).thenReturn(likeRk);
        when(likeRk.getDeleted()).thenReturn("rk.post.comment.like.deleted");

        publisher.publish(outboxEvent);

        verify(rabbitTemplate).convertAndSend("x.post.events", "rk.post.comment.like.deleted", payload);
    }
}
