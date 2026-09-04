package com.app.postcommandservice.collab.infrastructure.rabbitmq;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import com.app.postcommandservice.collab.domain.model.valueobj.CollabMemberRole;
import com.app.postcommandservice.collab.domain.model.valueobj.CollabMemberStatus;
import com.app.postcommandservice.collab.infrastructure.events.CollabJoinRequestCreatedEvent;
import com.app.postcommandservice.shared.infrastructure.entity.OutboxEvent;
import com.app.postcommandservice.shared.infrastructure.enums.EventStatus;
import com.app.postcommandservice.shared.infrastructure.mapper.JsonMapper;
import com.app.postcommandservice.shared.infrastructure.rabbitmq.config.RabbitMQProperties;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CollabJoinRequestCreatedEventPublisherTest {

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
    private RabbitMQProperties.RoutingKeys.PostRk.CollabRk collabRk;

    @Mock
    private RabbitMQProperties.RoutingKeys.PostRk.CollabRk.RequestRk requestRk;

    @InjectMocks
    private CollabJoinRequestCreatedEventPublisher publisher;

    @Test
    void shouldPublishCollabJoinRequestCreatedEventToRabbitMqUsingConfiguredRoutingKey() {
        var payload = CollabJoinRequestCreatedEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(UUID.randomUUID())
                .occurredAt(Instant.now())
                .collabId(UUID.randomUUID())
                .userId(UUID.randomUUID())
                .status(CollabMemberStatus.PENDING)
                .role(CollabMemberRole.MEMBER)
                .createdAt(Instant.now())
                .build();
        var outboxEvent = OutboxEvent.builder()
                .id(payload.id())
                .correlationId(payload.correlationId())
                .payload("{json}")
                .eventType(CollabJoinRequestCreatedEvent.class.getSimpleName())
                .status(EventStatus.PENDING)
                .createdAt(payload.occurredAt())
                .build();

        when(jsonMapper.fromJson("{json}", CollabJoinRequestCreatedEvent.class)).thenReturn(payload);
        when(rabbitMQProperties.getExchange()).thenReturn(exchanges);
        when(exchanges.getPost()).thenReturn(postExchange);
        when(postExchange.getEvents()).thenReturn("x.post.events");
        when(rabbitMQProperties.getRk()).thenReturn(routingKeys);
        when(routingKeys.getPost()).thenReturn(postRk);
        when(postRk.getCollab()).thenReturn(collabRk);
        when(collabRk.getRequest()).thenReturn(requestRk);
        when(requestRk.getCreated()).thenReturn("rk.post.collab.request.created");

        publisher.publish(outboxEvent);

        verify(rabbitTemplate).convertAndSend("x.post.events", "rk.post.collab.request.created", payload);
    }
}
