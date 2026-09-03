package com.app.postcommandservice.collab.infrastructure.rabbitmq;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import com.app.postcommandservice.collab.domain.model.valueobj.ColabStatus;
import com.app.postcommandservice.collab.domain.model.valueobj.CollabMemberRole;
import com.app.postcommandservice.collab.domain.model.valueobj.CollabMemberStatus;
import com.app.postcommandservice.collab.infrastructure.events.CollabOpenedEvent;
import com.app.postcommandservice.post.domain.model.valueobj.PostType;
import com.app.postcommandservice.shared.infrastructure.entity.OutboxEvent;
import com.app.postcommandservice.shared.infrastructure.enums.EventStatus;
import com.app.postcommandservice.shared.infrastructure.mapper.JsonMapper;
import com.app.postcommandservice.shared.infrastructure.rabbitmq.config.RabbitMQProperties;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CollabOpenedEventPublisherTest {

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

    @InjectMocks
    private CollabOpenedEventPublisher publisher;

    @Test
    void shouldPublishCollabOpenedEventToRabbitMqUsingConfiguredRoutingKey() {
        var payload = CollabOpenedEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(UUID.randomUUID())
                .occurredAt(Instant.now())
                .collabId(UUID.randomUUID())
                .title("collab")
                .createdBy(UUID.randomUUID())
                .collabStatus(ColabStatus.OPEN)
                .collabCreatedAt(Instant.now())
                .creatorMemberStatus(CollabMemberStatus.ACCEPTED)
                .creatorRole(CollabMemberRole.ADMIN)
                .creatorMemberCreatedAt(Instant.now())
                .postId(UUID.randomUUID())
                .userId(UUID.randomUUID())
                .postCollabId(UUID.randomUUID())
                .postType(PostType.COLAB)
                .description("description")
                .taggedUsers(Set.of("alice"))
                .postTags(Set.of("spring"))
                .postCreatedAt(Instant.now())
                .postUpdatedAt(Instant.now())
                .build();
        var outboxEvent = OutboxEvent.builder()
                .id(payload.id())
                .correlationId(payload.correlationId())
                .payload("{json}")
                .eventType(CollabOpenedEvent.class.getSimpleName())
                .status(EventStatus.PENDING)
                .createdAt(payload.occurredAt())
                .build();

        when(jsonMapper.fromJson("{json}", CollabOpenedEvent.class)).thenReturn(payload);
        when(rabbitMQProperties.getExchange()).thenReturn(exchanges);
        when(exchanges.getPost()).thenReturn(postExchange);
        when(postExchange.getEvents()).thenReturn("x.post.events");
        when(rabbitMQProperties.getRk()).thenReturn(routingKeys);
        when(routingKeys.getPost()).thenReturn(postRk);
        when(postRk.getCollab()).thenReturn(collabRk);
        when(collabRk.getOpened()).thenReturn("rk.post.collab.opened");

        publisher.publish(outboxEvent);

        verify(rabbitTemplate).convertAndSend("x.post.events", "rk.post.collab.opened", payload);
    }
}
