package com.app.postcommandservice.commentlike.infrastructure.rabbitmq;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import com.app.postcommandservice.commentlike.application.commands.ValidateCommentLikeCommand;
import com.app.postcommandservice.commentlike.domain.model.CommentLikeSource;
import com.app.postcommandservice.shared.infrastructure.entity.OutboxEvent;
import com.app.postcommandservice.shared.infrastructure.mapper.JsonMapper;
import com.app.postcommandservice.shared.infrastructure.rabbitmq.config.RabbitMQProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ValidateCommentLikeCommandPublisherTest {

    @Mock
    private RabbitTemplate rabbitTemplate;

    @Mock
    private RabbitMQProperties rabbitMQProperties;

    @Mock
    private JsonMapper jsonMapper;

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
    private ValidateCommentLikeCommandPublisher publisher;

    @Test
    void shouldSupportCommentLikeCommandOutboxEventType() {
        assertThat(publisher.supports(ValidateCommentLikeCommand.class.getSimpleName())).isTrue();
        assertThat(publisher.supports("PostCreatedEvent")).isFalse();
    }

    @Test
    void shouldPublishValidateCommentLikeCommandUsingConfiguredExchangeAndRoutingKey() {
        var command = new ValidateCommentLikeCommand(
                UUID.randomUUID(),
                UUID.randomUUID(),
                Instant.now(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                CommentLikeSource.HOME_FEED,
                1
        );

        when(rabbitMQProperties.getExchange()).thenReturn(exchanges);
        when(exchanges.getPost()).thenReturn(postExchange);
        when(postExchange.getCommands()).thenReturn("x.post.commands");
        when(rabbitMQProperties.getRk()).thenReturn(routingKeys);
        when(routingKeys.getPost()).thenReturn(postRk);
        when(postRk.getComment()).thenReturn(commentRk);
        when(commentRk.getLike()).thenReturn(likeRk);
        when(likeRk.getValidate()).thenReturn("rk.post.comment.like.validate");

        publisher.publish(command);

        verify(rabbitTemplate).convertAndSend("x.post.commands", "rk.post.comment.like.validate", command);
    }

    @Test
    void shouldPublishValidateCommentLikeCommandFromOutboxPayload() {
        var command = new ValidateCommentLikeCommand(
                UUID.randomUUID(),
                UUID.randomUUID(),
                Instant.now(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                CommentLikeSource.SEARCH,
                2
        );
        var outboxEvent = OutboxEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(command.correlationId())
                .payload("{\"type\":\"comment-like\"}")
                .eventType(ValidateCommentLikeCommand.class.getSimpleName())
                .build();

        when(rabbitMQProperties.getExchange()).thenReturn(exchanges);
        when(exchanges.getPost()).thenReturn(postExchange);
        when(postExchange.getCommands()).thenReturn("x.post.commands");
        when(rabbitMQProperties.getRk()).thenReturn(routingKeys);
        when(routingKeys.getPost()).thenReturn(postRk);
        when(postRk.getComment()).thenReturn(commentRk);
        when(commentRk.getLike()).thenReturn(likeRk);
        when(likeRk.getValidate()).thenReturn("rk.post.comment.like.validate");
        when(jsonMapper.fromJson(outboxEvent.getPayload(), ValidateCommentLikeCommand.class)).thenReturn(command);

        publisher.publish(outboxEvent);

        verify(rabbitTemplate).convertAndSend("x.post.commands", "rk.post.comment.like.validate", command);
    }
}
