package com.app.postcommandservice.collab.infrastructure.rabbitmq;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.app.postcommandservice.collab.infrastructure.events.CollabClosedEvent;
import com.app.postcommandservice.collab.infrastructure.events.CollabJoinInstructionsUpdatedEvent;
import com.app.postcommandservice.shared.infrastructure.entity.OutboxEvent;
import com.app.postcommandservice.shared.infrastructure.enums.EventStatus;
import com.app.postcommandservice.shared.infrastructure.outbox.OutboxDestination;
import com.app.postcommandservice.shared.infrastructure.rabbitmq.config.RabbitMQProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CollabJoinInstructionsUpdatedEventPublisherTest {

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
    private RabbitMQProperties.RoutingKeys.PostRk.CollabRk collabRk;

    @Mock
    private RabbitMQProperties.RoutingKeys.PostRk.CollabRk.JoinInstructionsRk joinInstructionsRk;

    @InjectMocks
    private CollabJoinInstructionsUpdatedEventPublisher publisher;

    @Test
    void shouldSupportOnlyTheJoinInstructionsUpdatedEventType() {
        assertThat(publisher.supports(CollabJoinInstructionsUpdatedEvent.class.getSimpleName())).isTrue();
        assertThat(publisher.supports(CollabClosedEvent.class.getSimpleName())).isFalse();
        assertThat(publisher.supports(null)).isFalse();
    }

    @Test
    void shouldResolveTheDestinationFromTheConfiguredExchangeAndRoutingKey() {
        var outboxEvent = OutboxEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(UUID.randomUUID())
                .payload("{json}")
                .eventType(CollabJoinInstructionsUpdatedEvent.class.getSimpleName())
                .status(EventStatus.PENDING)
                .createdAt(Instant.now())
                .build();
        when(rabbitMQProperties.getExchange()).thenReturn(exchanges);
        when(exchanges.getPost()).thenReturn(postExchange);
        when(postExchange.getEvents()).thenReturn("x.post.events");
        when(rabbitMQProperties.getRk()).thenReturn(routingKeys);
        when(routingKeys.getPost()).thenReturn(postRk);
        when(postRk.getCollab()).thenReturn(collabRk);
        when(collabRk.getJoinInstructions()).thenReturn(joinInstructionsRk);
        when(joinInstructionsRk.getUpdated()).thenReturn("rk.post.collab.join-instructions.updated");

        assertThat(publisher.destination(outboxEvent))
                .isEqualTo(new OutboxDestination("x.post.events", "rk.post.collab.join-instructions.updated"));
    }
}
