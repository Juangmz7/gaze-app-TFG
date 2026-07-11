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
import com.app.socialservice.user.application.service.UserStatsService;
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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FollowRabbitMQListenerTest {

    @Mock
    private FollowNodeService followNodeService;

    @Mock
    private UserStatsService userStatsService;

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
    void shouldTrackUserFollowedEventProcessingSeparatelyForNeo4jAndPostgres() {
        var followerId = UUID.randomUUID();
        var followedId = UUID.randomUUID();
        var event = userFollowedEvent(followerId, followedId);
        stubUnprocessedFollowEvent(event);

        followRabbitMQListener.onUserFollowed(event);

        InOrder inOrder = inOrder(followNodeService, processedEventsRepository, userStatsService);
        inOrder.verify(followNodeService).createFollowRelationship(followerId, followedId);
        inOrder.verify(processedEventsRepository).insertIfAbsent(
                event.id(),
                TargetDatabase.NEO4J.name(),
                event.correlationId(),
                UserFollowedEvent.class.getSimpleName()
        );
        inOrder.verify(userStatsService).incrementFollowCounters(followerId, followedId);
        inOrder.verify(processedEventsRepository).insertIfAbsent(
                event.id(),
                TargetDatabase.POSTGRES.name(),
                event.correlationId(),
                UserFollowedEvent.class.getSimpleName()
        );
    }

    @Test
    void shouldNotSkipNeo4jHandlerWhenTheSameCorrelationIdWasAlreadyProcessedByAPostgresHandler() {
        var event = userFollowedEvent(UUID.randomUUID(), UUID.randomUUID());
        when(processedEventsRepository.existsByIdAndTargetDatabase(event.id(), TargetDatabase.NEO4J)).thenReturn(false);
        when(processedEventsRepository.existsByCorrelationIdAndTargetDatabase(
                event.correlationId(),
                TargetDatabase.NEO4J
        )).thenReturn(false);
        when(processedEventsRepository.existsByIdAndTargetDatabase(event.id(), TargetDatabase.POSTGRES)).thenReturn(false);
        when(processedEventsRepository.existsByCorrelationIdAndTargetDatabase(
                event.correlationId(),
                TargetDatabase.POSTGRES
        )).thenReturn(true);

        followRabbitMQListener.onUserFollowed(event);

        verify(followNodeService).createFollowRelationship(event.followerUserId(), event.followedUserId());
        verify(userStatsService, never()).incrementFollowCounters(any(), any());
        verify(processedEventsRepository).insertIfAbsent(
                event.id(),
                TargetDatabase.NEO4J.name(),
                event.correlationId(),
                UserFollowedEvent.class.getSimpleName()
        );
    }

    @Test
    void shouldNotSkipPostgresHandlerWhenTheSameCorrelationIdWasAlreadyProcessedByANeo4jHandler() {
        var event = userFollowedEvent(UUID.randomUUID(), UUID.randomUUID());
        when(processedEventsRepository.existsByIdAndTargetDatabase(event.id(), TargetDatabase.NEO4J)).thenReturn(false);
        when(processedEventsRepository.existsByCorrelationIdAndTargetDatabase(
                event.correlationId(),
                TargetDatabase.NEO4J
        )).thenReturn(true);
        when(processedEventsRepository.existsByIdAndTargetDatabase(event.id(), TargetDatabase.POSTGRES)).thenReturn(false);
        when(processedEventsRepository.existsByCorrelationIdAndTargetDatabase(
                event.correlationId(),
                TargetDatabase.POSTGRES
        )).thenReturn(false);

        followRabbitMQListener.onUserFollowed(event);

        verify(followNodeService, never()).createFollowRelationship(any(), any());
        verify(userStatsService).incrementFollowCounters(event.followerUserId(), event.followedUserId());
        verify(processedEventsRepository).insertIfAbsent(
                event.id(),
                TargetDatabase.POSTGRES.name(),
                event.correlationId(),
                UserFollowedEvent.class.getSimpleName()
        );
    }

    @Test
    void shouldSkipDuplicateEventWithinBothTargetDatabases() {
        var event = userFollowedEvent(UUID.randomUUID(), UUID.randomUUID());
        when(processedEventsRepository.existsByIdAndTargetDatabase(event.id(), TargetDatabase.NEO4J)).thenReturn(true);
        when(processedEventsRepository.existsByIdAndTargetDatabase(event.id(), TargetDatabase.POSTGRES)).thenReturn(true);

        followRabbitMQListener.onUserFollowed(event);

        verify(followNodeService, never()).createFollowRelationship(any(), any());
        verify(userStatsService, never()).incrementFollowCounters(any(), any());
        verify(processedEventsRepository, never()).insertIfAbsent(any(), any(), any(), any());
    }

    @Test
    void shouldNotMarkTheNeo4jTargetAsProcessedWhenTheNeo4jOperationFails() {
        var event = userFollowedEvent(UUID.randomUUID(), UUID.randomUUID());
        stubUnprocessedFollowEvent(event);
        doThrow(new RuntimeException("neo4j follow sync failed"))
                .when(followNodeService)
                .createFollowRelationship(event.followerUserId(), event.followedUserId());

        assertThatThrownBy(() -> followRabbitMQListener.onUserFollowed(event))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("neo4j follow sync failed");

        verify(userStatsService).incrementFollowCounters(event.followerUserId(), event.followedUserId());
        verify(processedEventsRepository, never()).insertIfAbsent(
                event.id(),
                TargetDatabase.NEO4J.name(),
                event.correlationId(),
                UserFollowedEvent.class.getSimpleName()
        );
        verify(processedEventsRepository).insertIfAbsent(
                event.id(),
                TargetDatabase.POSTGRES.name(),
                event.correlationId(),
                UserFollowedEvent.class.getSimpleName()
        );
    }

    @Test
    void shouldNotMarkThePostgresTargetAsProcessedWhenThePostgresOperationFails() {
        var event = userFollowedEvent(UUID.randomUUID(), UUID.randomUUID());
        stubUnprocessedFollowEvent(event);
        doThrow(new RuntimeException("postgres counter sync failed"))
                .when(userStatsService)
                .incrementFollowCounters(event.followerUserId(), event.followedUserId());

        assertThatThrownBy(() -> followRabbitMQListener.onUserFollowed(event))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("postgres counter sync failed");

        verify(followNodeService).createFollowRelationship(event.followerUserId(), event.followedUserId());
        verify(processedEventsRepository).insertIfAbsent(
                event.id(),
                TargetDatabase.NEO4J.name(),
                event.correlationId(),
                UserFollowedEvent.class.getSimpleName()
        );
        verify(processedEventsRepository, never()).insertIfAbsent(
                event.id(),
                TargetDatabase.POSTGRES.name(),
                event.correlationId(),
                UserFollowedEvent.class.getSimpleName()
        );
    }

    @Test
    void shouldRejectUserFollowedEventToDlqWhenDomainRuleFails() {
        var event = userFollowedEvent(UUID.randomUUID(), UUID.randomUUID());
        stubUnprocessedFollowEvent(event);
        doThrow(new FollowBlockedException("follow relationship is blocked"))
                .when(followNodeService)
                .createFollowRelationship(event.followerUserId(), event.followedUserId());

        assertThatThrownBy(() -> followRabbitMQListener.onUserFollowed(event))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class)
                .hasCauseInstanceOf(FollowBlockedException.class)
                .hasMessage("follow relationship is blocked");

        verify(userStatsService).incrementFollowCounters(event.followerUserId(), event.followedUserId());
        verify(processedEventsRepository, never()).insertIfAbsent(
                event.id(),
                TargetDatabase.NEO4J.name(),
                event.correlationId(),
                UserFollowedEvent.class.getSimpleName()
        );
    }

    @Test
    void shouldTrackUserUnfollowedEventProcessingSeparatelyForNeo4jAndPostgres() {
        var followerId = UUID.randomUUID();
        var followedId = UUID.randomUUID();
        var event = userUnfollowedEvent(followerId, followedId);
        stubUnprocessedUnfollowEvent(event);

        followRabbitMQListener.onUserUnfollowed(event);

        InOrder inOrder = inOrder(followNodeService, processedEventsRepository, userStatsService);
        inOrder.verify(followNodeService).deleteFollowRelationship(followerId, followedId);
        inOrder.verify(processedEventsRepository).insertIfAbsent(
                event.id(),
                TargetDatabase.NEO4J.name(),
                event.correlationId(),
                UserUnfollowedEvent.class.getSimpleName()
        );
        inOrder.verify(userStatsService).decrementFollowCounters(followerId, followedId);
        inOrder.verify(processedEventsRepository).insertIfAbsent(
                event.id(),
                TargetDatabase.POSTGRES.name(),
                event.correlationId(),
                UserUnfollowedEvent.class.getSimpleName()
        );
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
        verify(userStatsService, never()).incrementFollowCounters(any(), any());
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
        verify(userStatsService, never()).decrementFollowCounters(any(), any());
        verify(processedEventsRepository, never()).insertIfAbsent(any(), any(), any(), any());
    }

    private void stubUnprocessedFollowEvent(UserFollowedEvent event) {
        when(processedEventsRepository.existsByIdAndTargetDatabase(event.id(), TargetDatabase.NEO4J)).thenReturn(false);
        when(processedEventsRepository.existsByCorrelationIdAndTargetDatabase(
                event.correlationId(),
                TargetDatabase.NEO4J
        )).thenReturn(false);
        when(processedEventsRepository.existsByIdAndTargetDatabase(event.id(), TargetDatabase.POSTGRES)).thenReturn(false);
        when(processedEventsRepository.existsByCorrelationIdAndTargetDatabase(
                event.correlationId(),
                TargetDatabase.POSTGRES
        )).thenReturn(false);
    }

    private void stubUnprocessedUnfollowEvent(UserUnfollowedEvent event) {
        when(processedEventsRepository.existsByIdAndTargetDatabase(event.id(), TargetDatabase.NEO4J)).thenReturn(false);
        when(processedEventsRepository.existsByCorrelationIdAndTargetDatabase(
                event.correlationId(),
                TargetDatabase.NEO4J
        )).thenReturn(false);
        when(processedEventsRepository.existsByIdAndTargetDatabase(event.id(), TargetDatabase.POSTGRES)).thenReturn(false);
        when(processedEventsRepository.existsByCorrelationIdAndTargetDatabase(
                event.correlationId(),
                TargetDatabase.POSTGRES
        )).thenReturn(false);
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
