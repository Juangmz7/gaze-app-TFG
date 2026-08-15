package com.app.postcommandservice.like.infrastructure.rabbitmq;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import com.app.postcommandservice.like.application.commands.ValidatePostLikeCommand;
import com.app.postcommandservice.like.application.commands.ValidatePostUnlikeCommand;
import com.app.postcommandservice.like.domain.model.PostLikeSource;
import com.app.postcommandservice.shared.infrastructure.entity.OutboxEvent;
import com.app.postcommandservice.shared.infrastructure.mapper.JsonMapper;
import com.app.postcommandservice.shared.infrastructure.rabbitmq.config.RabbitMQProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ValidatePostLikeCommandPublisherTest {

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
    private RabbitMQProperties.RoutingKeys.PostRk.LikeRk likeRk;

    @InjectMocks
    private ValidatePostLikeCommandPublisher publisher;

    @Test
    void shouldSupportLikeAndUnlikeCommandOutboxEventTypes() {
        assertThat(publisher.supports(ValidatePostLikeCommand.class.getSimpleName())).isTrue();
        assertThat(publisher.supports(ValidatePostUnlikeCommand.class.getSimpleName())).isTrue();
        assertThat(publisher.supports("PostCreatedEvent")).isFalse();
    }

    @Test
    void shouldPublishValidatePostLikeCommandUsingConfiguredExchangeAndRoutingKey() {
        var command = new ValidatePostLikeCommand(
                UUID.randomUUID(),
                UUID.randomUUID(),
                Instant.now(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                PostLikeSource.HOME_FEED,
                1
        );

        when(rabbitMQProperties.getExchange()).thenReturn(exchanges);
        when(exchanges.getPost()).thenReturn(postExchange);
        when(postExchange.getCommands()).thenReturn("x.post.commands");
        when(rabbitMQProperties.getRk()).thenReturn(routingKeys);
        when(routingKeys.getPost()).thenReturn(postRk);
        when(postRk.getLike()).thenReturn(likeRk);
        when(likeRk.getValidate()).thenReturn("rk.post.like.validate");

        publisher.publish(command);

        verify(rabbitTemplate).convertAndSend("x.post.commands", "rk.post.like.validate", command);
    }

    @Mock
    private RabbitMQProperties.RoutingKeys.PostRk.UnlikeRk unlikeRk;

    @Test
    void shouldPublishValidatePostUnlikeCommandUsingConfiguredExchangeAndRoutingKey() {
        var command = new ValidatePostUnlikeCommand(
                UUID.randomUUID(),
                UUID.randomUUID(),
                Instant.now(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                PostLikeSource.SEARCH,
                4
        );

        when(rabbitMQProperties.getExchange()).thenReturn(exchanges);
        when(exchanges.getPost()).thenReturn(postExchange);
        when(postExchange.getCommands()).thenReturn("x.post.commands");
        when(rabbitMQProperties.getRk()).thenReturn(routingKeys);
        when(routingKeys.getPost()).thenReturn(postRk);
        when(postRk.getUnlike()).thenReturn(unlikeRk);
        when(unlikeRk.getValidate()).thenReturn("rk.post.unlike.validate");

        publisher.publish(command);

        verify(rabbitTemplate).convertAndSend("x.post.commands", "rk.post.unlike.validate", command);
    }

    @Test
    void shouldPublishValidatePostLikeCommandFromOutboxPayload() {
        var command = new ValidatePostLikeCommand(
                UUID.randomUUID(),
                UUID.randomUUID(),
                Instant.now(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                PostLikeSource.HOME_FEED,
                2
        );
        var outboxEvent = OutboxEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(command.correlationId())
                .payload("{\"type\":\"like\"}")
                .eventType(ValidatePostLikeCommand.class.getSimpleName())
                .build();

        when(rabbitMQProperties.getExchange()).thenReturn(exchanges);
        when(exchanges.getPost()).thenReturn(postExchange);
        when(postExchange.getCommands()).thenReturn("x.post.commands");
        when(rabbitMQProperties.getRk()).thenReturn(routingKeys);
        when(routingKeys.getPost()).thenReturn(postRk);
        when(postRk.getLike()).thenReturn(likeRk);
        when(likeRk.getValidate()).thenReturn("rk.post.like.validate");
        when(jsonMapper.fromJson(outboxEvent.getPayload(), ValidatePostLikeCommand.class)).thenReturn(command);

        publisher.publish(outboxEvent);

        verify(rabbitTemplate).convertAndSend("x.post.commands", "rk.post.like.validate", command);
    }

    @Test
    void shouldPublishValidatePostUnlikeCommandFromOutboxPayload() {
        var command = new ValidatePostUnlikeCommand(
                UUID.randomUUID(),
                UUID.randomUUID(),
                Instant.now(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                PostLikeSource.SEARCH,
                5
        );
        var outboxEvent = OutboxEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(command.correlationId())
                .payload("{\"type\":\"unlike\"}")
                .eventType(ValidatePostUnlikeCommand.class.getSimpleName())
                .build();

        when(rabbitMQProperties.getExchange()).thenReturn(exchanges);
        when(exchanges.getPost()).thenReturn(postExchange);
        when(postExchange.getCommands()).thenReturn("x.post.commands");
        when(rabbitMQProperties.getRk()).thenReturn(routingKeys);
        when(routingKeys.getPost()).thenReturn(postRk);
        when(postRk.getUnlike()).thenReturn(unlikeRk);
        when(unlikeRk.getValidate()).thenReturn("rk.post.unlike.validate");
        when(jsonMapper.fromJson(outboxEvent.getPayload(), ValidatePostUnlikeCommand.class)).thenReturn(command);

        publisher.publish(outboxEvent);

        verify(rabbitTemplate).convertAndSend("x.post.commands", "rk.post.unlike.validate", command);
    }
}
