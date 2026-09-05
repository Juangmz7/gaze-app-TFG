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
import com.app.postcommandservice.collab.infrastructure.events.CollabMemberBannedEvent;
import com.app.postcommandservice.shared.infrastructure.entity.OutboxEvent;
import com.app.postcommandservice.shared.infrastructure.enums.EventStatus;
import com.app.postcommandservice.shared.infrastructure.mapper.JsonMapper;
import com.app.postcommandservice.shared.infrastructure.rabbitmq.config.RabbitMQProperties;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CollabMemberBannedEventPublisherTest {

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
    private RabbitMQProperties.RoutingKeys.PostRk.CollabRk.MemberRk memberRk;

    @InjectMocks
    private CollabMemberBannedEventPublisher publisher;

    @Test
    void shouldPublishCollabMemberBannedEventToRabbitMqUsingConfiguredRoutingKey() {
        var payload = CollabMemberBannedEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(UUID.randomUUID())
                .occurredAt(Instant.now())
                .collabId(UUID.randomUUID())
                .userId(UUID.randomUUID())
                .collabMemberStatus(CollabMemberStatus.BANNED)
                .role(CollabMemberRole.MEMBER)
                .memberCreatedAt(Instant.now())
                .build();
        var outboxEvent = OutboxEvent.builder()
                .id(payload.id())
                .correlationId(payload.correlationId())
                .payload("{json}")
                .eventType(CollabMemberBannedEvent.class.getSimpleName())
                .status(EventStatus.PENDING)
                .createdAt(payload.occurredAt())
                .build();

        when(jsonMapper.fromJson("{json}", CollabMemberBannedEvent.class)).thenReturn(payload);
        when(rabbitMQProperties.getExchange()).thenReturn(exchanges);
        when(exchanges.getPost()).thenReturn(postExchange);
        when(postExchange.getEvents()).thenReturn("x.post.events");
        when(rabbitMQProperties.getRk()).thenReturn(routingKeys);
        when(routingKeys.getPost()).thenReturn(postRk);
        when(postRk.getCollab()).thenReturn(collabRk);
        when(collabRk.getMember()).thenReturn(memberRk);
        when(memberRk.getBanned()).thenReturn("rk.post.collab.member.banned");

        publisher.publish(outboxEvent);

        verify(rabbitTemplate).convertAndSend("x.post.events", "rk.post.collab.member.banned", payload);
    }
}
