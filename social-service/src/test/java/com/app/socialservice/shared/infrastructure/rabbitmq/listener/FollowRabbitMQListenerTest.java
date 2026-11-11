package com.app.socialservice.shared.infrastructure.rabbitmq.listener;

import java.time.Instant;
import java.util.UUID;

import com.app.socialservice.follow.application.service.FollowNodeService;
import com.app.socialservice.follow.domain.exception.FollowBlockedException;
import com.app.socialservice.follow.infrastructure.events.UserFollowedEvent;
import com.app.socialservice.follow.infrastructure.events.UserUnfollowedEvent;
import com.app.socialservice.shared.infrastructure.entity.TargetDatabase;
import com.app.socialservice.shared.infrastructure.rabbitmq.config.RabbitMQProperties;
import com.app.socialservice.shared.infrastructure.repository.ProcessedEventsRepository;
import com.app.socialservice.user.application.service.UserCacheEvictor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FollowRabbitMQListenerTest {

    @Mock
    private FollowNodeService followNodeService;

    @Mock
    private UserCacheEvictor userCacheEvictor;

    @Mock
    private ProcessedEventsRepository processedEventsRepository;

    @Spy
    private RabbitMQProperties rabbitMQProperties = new RabbitMQProperties();

    @InjectMocks
    private FollowRabbitMQListener followRabbitMQListener;

    @BeforeEach
    void setUp() {
        rabbitMQProperties.getQueue().getUser().getFollow().setCreated("q.social-service.user.follow.created");
        rabbitMQProperties.getQueue().getUser().getFollow().setDeleted("q.social-service.user.follow.deleted");
    }

    @Test
    void shouldProjectTheFollowIntoNeo4jThenEvictTheFollowersRecommendations() {
        var followerId = UUID.randomUUID();
        var followedId = UUID.randomUUID();
        var event = userFollowedEvent(followerId, followedId);
        stubUnprocessed(event.id(), event.correlationId());

        followRabbitMQListener.onUserFollowed(event);

        InOrder inOrder = inOrder(followNodeService, processedEventsRepository, userCacheEvictor);
        inOrder.verify(followNodeService).createFollowRelationship(followerId, followedId);
        inOrder.verify(processedEventsRepository).insertIfAbsent(
                event.id(),
                TargetDatabase.NEO4J.name(),
                event.correlationId(),
                UserFollowedEvent.class.getSimpleName()
        );
        inOrder.verify(userCacheEvictor).evictRecommendations(followerId);
    }

    @Test
    void shouldProjectTheUnfollowIntoNeo4jThenEvictTheFollowersRecommendations() {
        var followerId = UUID.randomUUID();
        var followedId = UUID.randomUUID();
        var event = userUnfollowedEvent(followerId, followedId);
        stubUnprocessed(event.id(), event.correlationId());

        followRabbitMQListener.onUserUnfollowed(event);

        InOrder inOrder = inOrder(followNodeService, processedEventsRepository, userCacheEvictor);
        inOrder.verify(followNodeService).deleteFollowRelationship(followerId, followedId);
        inOrder.verify(processedEventsRepository).insertIfAbsent(
                event.id(),
                TargetDatabase.NEO4J.name(),
                event.correlationId(),
                UserUnfollowedEvent.class.getSimpleName()
        );
        inOrder.verify(userCacheEvictor).evictRecommendations(followerId);
    }

    @Test
    void shouldSkipADuplicateEvent() {
        var event = userFollowedEvent(UUID.randomUUID(), UUID.randomUUID());
        when(processedEventsRepository.existsByIdAndTargetDatabase(event.id(), TargetDatabase.NEO4J)).thenReturn(true);

        followRabbitMQListener.onUserFollowed(event);

        verify(followNodeService, never()).createFollowRelationship(any(), any());
        verify(processedEventsRepository, never()).insertIfAbsent(any(), any(), any(), any());
        verifyNoInteractions(userCacheEvictor);
    }

    @Test
    void shouldNotMarkTheEventAsProcessedWhenTheNeo4jOperationFails() {
        var event = userFollowedEvent(UUID.randomUUID(), UUID.randomUUID());
        stubUnprocessed(event.id(), event.correlationId());
        doThrow(new RuntimeException("neo4j follow sync failed"))
                .when(followNodeService)
                .createFollowRelationship(event.followerUserId(), event.followedUserId());

        assertThatThrownBy(() -> followRabbitMQListener.onUserFollowed(event))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("neo4j follow sync failed");

        verify(processedEventsRepository, never()).insertIfAbsent(any(), any(), any(), any());
        verifyNoInteractions(userCacheEvictor);
    }

    @Test
    void shouldRejectUserFollowedEventToDlqWhenDomainRuleFails() {
        var event = userFollowedEvent(UUID.randomUUID(), UUID.randomUUID());
        stubUnprocessed(event.id(), event.correlationId());
        doThrow(new FollowBlockedException("follow relationship is blocked"))
                .when(followNodeService)
                .createFollowRelationship(event.followerUserId(), event.followedUserId());

        assertThatThrownBy(() -> followRabbitMQListener.onUserFollowed(event))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class)
                .hasCauseInstanceOf(FollowBlockedException.class)
                .hasMessage("follow relationship is blocked");

        verify(processedEventsRepository, never()).insertIfAbsent(any(), any(), any(), any());
    }

    @Test
    void shouldRejectInvalidUserFollowedEventPayload() {
        var duplicatedUserId = UUID.randomUUID();
        var event = UserFollowedEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(UUID.randomUUID())
                .occurredAt(null)
                .followerUserId(duplicatedUserId)
                .followedUserId(duplicatedUserId)
                .build();

        assertThatThrownBy(() -> followRabbitMQListener.onUserFollowed(event))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("event.occurredAt must not be null");

        verify(followNodeService, never()).createFollowRelationship(any(), any());
        verify(processedEventsRepository, never()).insertIfAbsent(any(), any(), any(), any());
    }

    @Test
    void shouldRejectInvalidUserUnfollowedEventPayload() {
        var duplicatedUserId = UUID.randomUUID();
        var event = UserUnfollowedEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(UUID.randomUUID())
                .occurredAt(null)
                .followerUserId(duplicatedUserId)
                .followedUserId(duplicatedUserId)
                .build();

        assertThatThrownBy(() -> followRabbitMQListener.onUserUnfollowed(event))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("event.occurredAt must not be null");

        verify(followNodeService, never()).deleteFollowRelationship(any(), any());
        verify(processedEventsRepository, never()).insertIfAbsent(any(), any(), any(), any());
    }

    private void stubUnprocessed(UUID eventId, UUID correlationId) {
        when(processedEventsRepository.existsByIdAndTargetDatabase(eventId, TargetDatabase.NEO4J)).thenReturn(false);
        when(processedEventsRepository.existsByCorrelationIdAndTargetDatabase(correlationId, TargetDatabase.NEO4J))
                .thenReturn(false);
    }

    private UserFollowedEvent userFollowedEvent(UUID followerId, UUID followedId) {
        return UserFollowedEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(UUID.randomUUID())
                .occurredAt(Instant.now())
                .followerUserId(followerId)
                .followedUserId(followedId)
                .build();
    }

    private UserUnfollowedEvent userUnfollowedEvent(UUID followerId, UUID followedId) {
        return UserUnfollowedEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(UUID.randomUUID())
                .occurredAt(Instant.now())
                .followerUserId(followerId)
                .followedUserId(followedId)
                .build();
    }
}
