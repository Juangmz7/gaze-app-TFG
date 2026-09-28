package com.app.postcommandservice.share.infrastructure.rabbitmq;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import com.app.postcommandservice.share.application.commands.CreatePostShareCommand;
import com.app.postcommandservice.shared.infrastructure.entity.OutboxEvent;
import com.app.postcommandservice.shared.infrastructure.mapper.JsonMapper;
import com.app.postcommandservice.shared.infrastructure.rabbitmq.config.RabbitMQProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CreatePostShareCommandPublisherTest {

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
    private RabbitMQProperties.RoutingKeys.PostRk.ShareRk shareRk;

    @Mock
    private RabbitMQProperties.RoutingKeys.PostRk.ShareRk.CreateRk createRk;

    @InjectMocks
    private CreatePostShareCommandPublisher publisher;

    @Test
    void shouldSupportCreatePostShareCommandOutboxEventType() {
        assertThat(publisher.supports(CreatePostShareCommand.class.getSimpleName())).isTrue();
        assertThat(publisher.supports("PostSharedEvent")).isFalse();
    }

    @Test
    void shouldPublishCreatePostShareCommandUsingConfiguredExchangeAndRoutingKey() {
        var command = command();

        when(rabbitMQProperties.getExchange()).thenReturn(exchanges);
        when(exchanges.getPost()).thenReturn(postExchange);
        when(postExchange.getCommands()).thenReturn("x.post.commands");
        when(rabbitMQProperties.getRk()).thenReturn(routingKeys);
        when(routingKeys.getPost()).thenReturn(postRk);
        when(postRk.getShare()).thenReturn(shareRk);
        when(shareRk.getCreate()).thenReturn(createRk);
        when(createRk.getValidate()).thenReturn("rk.post.share.create.validate");

        publisher.publish(command);

        verify(rabbitTemplate).convertAndSend("x.post.commands", "rk.post.share.create.validate", command);
    }

    @Test
    void shouldPublishCreatePostShareCommandFromOutboxPayload() {
        var command = command();
        var outboxEvent = OutboxEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(command.correlationId())
                .payload("{\"type\":\"share\"}")
                .eventType(CreatePostShareCommand.class.getSimpleName())
                .build();

        when(rabbitMQProperties.getExchange()).thenReturn(exchanges);
        when(exchanges.getPost()).thenReturn(postExchange);
        when(postExchange.getCommands()).thenReturn("x.post.commands");
        when(rabbitMQProperties.getRk()).thenReturn(routingKeys);
        when(routingKeys.getPost()).thenReturn(postRk);
        when(postRk.getShare()).thenReturn(shareRk);
        when(shareRk.getCreate()).thenReturn(createRk);
        when(createRk.getValidate()).thenReturn("rk.post.share.create.validate");
        when(jsonMapper.fromJson(outboxEvent.getPayload(), CreatePostShareCommand.class)).thenReturn(command);

        publisher.publish(outboxEvent);

        verify(rabbitTemplate).convertAndSend("x.post.commands", "rk.post.share.create.validate", command);
    }

    private CreatePostShareCommand command() {
        return new CreatePostShareCommand(
                UUID.randomUUID(),
                UUID.randomUUID(),
                Instant.now(),
                UUID.randomUUID(),
                UUID.randomUUID()
        );
    }
}
