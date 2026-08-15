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
import com.app.postcommandservice.view.application.commands.ProcessPostViewCommand;
import com.app.postcommandservice.view.domain.model.PostViewExitReason;
import com.app.postcommandservice.view.domain.model.PostViewSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProcessPostViewCommandPublisherTest {

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
    private RabbitMQProperties.RoutingKeys.PostRk.ViewRk viewRk;

    @InjectMocks
    private ProcessPostViewCommandPublisher publisher;

    @Test
    void shouldSupportOnlyProcessPostViewCommandOutboxEventType() {
        assertThat(publisher.supports(ProcessPostViewCommand.class.getSimpleName())).isTrue();
        assertThat(publisher.supports("ValidatePostLikeCommand")).isFalse();
    }

    @Test
    void shouldPublishProcessPostViewCommandUsingConfiguredExchangeAndRoutingKey() {
        var command = new ProcessPostViewCommand(
                UUID.randomUUID(),
                UUID.randomUUID(),
                Instant.now(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                PostViewSource.HOME_FEED,
                0,
                1200,
                800,
                67,
                PostViewExitReason.APP_BACKGROUNDED
        );

        when(rabbitMQProperties.getExchange()).thenReturn(exchanges);
        when(exchanges.getPost()).thenReturn(postExchange);
        when(postExchange.getCommands()).thenReturn("x.post.commands");
        when(rabbitMQProperties.getRk()).thenReturn(routingKeys);
        when(routingKeys.getPost()).thenReturn(postRk);
        when(postRk.getView()).thenReturn(viewRk);
        when(viewRk.getProcess()).thenReturn("rk.post.view.process");

        publisher.publish(command);

        verify(rabbitTemplate).convertAndSend("x.post.commands", "rk.post.view.process", command);
    }

    @Test
    void shouldPublishProcessPostViewCommandFromOutboxPayload() {
        var command = new ProcessPostViewCommand(
                UUID.randomUUID(),
                UUID.randomUUID(),
                Instant.now(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                PostViewSource.HOME_FEED,
                0,
                1200,
                800,
                67,
                PostViewExitReason.APP_BACKGROUNDED
        );
        var outboxEvent = OutboxEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(command.correlationId())
                .payload("{\"type\":\"view\"}")
                .eventType(ProcessPostViewCommand.class.getSimpleName())
                .build();

        when(rabbitMQProperties.getExchange()).thenReturn(exchanges);
        when(exchanges.getPost()).thenReturn(postExchange);
        when(postExchange.getCommands()).thenReturn("x.post.commands");
        when(rabbitMQProperties.getRk()).thenReturn(routingKeys);
        when(routingKeys.getPost()).thenReturn(postRk);
        when(postRk.getView()).thenReturn(viewRk);
        when(viewRk.getProcess()).thenReturn("rk.post.view.process");
        when(jsonMapper.fromJson(outboxEvent.getPayload(), ProcessPostViewCommand.class)).thenReturn(command);

        publisher.publish(outboxEvent);

        verify(rabbitTemplate).convertAndSend("x.post.commands", "rk.post.view.process", command);
    }
}
