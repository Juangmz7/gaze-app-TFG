package com.app.socialservice.shared.infrastructure.rabbitmq.listener;

import java.time.Instant;
import java.util.UUID;

import com.app.socialservice.follow.application.service.FollowNodeService;
import com.app.socialservice.follow.infrastructure.events.UserFollowedEvent;
import com.app.socialservice.follow.infrastructure.events.UserUnfollowedEvent;
import com.app.socialservice.shared.infrastructure.rabbitmq.config.RabbitMQProperties;
import com.app.socialservice.shared.infrastructure.repository.ProcessedEventsRepository;
import com.app.socialservice.user.application.service.UserStatsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
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
    void shouldDelegateUserFollowedEventToFollowNodeServiceAndRecordProcessedEvent() {
        var followerId = UUID.randomUUID();
        var followedId = UUID.randomUUID();
        var event = UserFollowedEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(UUID.randomUUID())
                .occurredAt(Instant.now())
                .followerUserId(followerId)
                .followedUserId(followedId)
                .build();
        when(processedEventsRepository.existsById(event.id())).thenReturn(false);
        when(processedEventsRepository.existsByCorrelationId(event.correlationId())).thenReturn(false);

        followRabbitMQListener.onUserFollowed(event);

        InOrder inOrder = inOrder(followNodeService, userStatsService, processedEventsRepository);
        inOrder.verify(followNodeService).createFollowRelationship(followerId, followedId);
        inOrder.verify(userStatsService).incrementFollowCounters(followerId, followedId);
        inOrder.verify(processedEventsRepository).insertIfAbsent(
                event.id(),
                event.correlationId(),
                UserFollowedEvent.class.getSimpleName()
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
        verify(processedEventsRepository, never()).insertIfAbsent(any(), any(), any());
    }

    @Test
    void shouldSkipFollowNodeSyncWhenFollowEventIdWasAlreadyProcessed() {
        var event = UserFollowedEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(UUID.randomUUID())
                .occurredAt(Instant.now())
                .followerUserId(UUID.randomUUID())
                .followedUserId(UUID.randomUUID())
                .build();
        when(processedEventsRepository.existsById(event.id())).thenReturn(true);

        followRabbitMQListener.onUserFollowed(event);

        verify(followNodeService, never()).createFollowRelationship(any(), any());
        verify(userStatsService, never()).incrementFollowCounters(any(), any());
        verify(processedEventsRepository, never()).insertIfAbsent(any(), any(), any());
    }

    @Test
    void shouldRethrowWhenFollowNodeSyncFails() {
        var event = UserFollowedEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(UUID.randomUUID())
                .occurredAt(Instant.now())
                .followerUserId(UUID.randomUUID())
                .followedUserId(UUID.randomUUID())
                .build();
        when(processedEventsRepository.existsById(event.id())).thenReturn(false);
        when(processedEventsRepository.existsByCorrelationId(event.correlationId())).thenReturn(false);

        doThrow(new RuntimeException("neo4j follow sync failed"))
                .when(followNodeService)
                .createFollowRelationship(event.followerUserId(), event.followedUserId());

        assertThatThrownBy(() -> followRabbitMQListener.onUserFollowed(event))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("neo4j follow sync failed");

        verify(userStatsService, never()).incrementFollowCounters(any(), any());
        verify(processedEventsRepository, never()).insertIfAbsent(any(), any(), any());
    }

    @Test
    void shouldDelegateUserUnfollowedEventToFollowNodeServiceAndRecordProcessedEvent() {
        var followerId = UUID.randomUUID();
        var followedId = UUID.randomUUID();
        var event = UserUnfollowedEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(UUID.randomUUID())
                .occurredAt(Instant.now())
                .followerUserId(followerId)
                .followedUserId(followedId)
                .build();
        when(processedEventsRepository.existsById(event.id())).thenReturn(false);
        when(processedEventsRepository.existsByCorrelationId(event.correlationId())).thenReturn(false);

        followRabbitMQListener.onUserUnfollowed(event);

        InOrder inOrder = inOrder(followNodeService, userStatsService, processedEventsRepository);
        inOrder.verify(followNodeService).deleteFollowRelationship(followerId, followedId);
        inOrder.verify(userStatsService).decrementFollowCounters(followerId, followedId);
        inOrder.verify(processedEventsRepository).insertIfAbsent(
                event.id(),
                event.correlationId(),
                UserUnfollowedEvent.class.getSimpleName()
        );
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
        verify(processedEventsRepository, never()).insertIfAbsent(any(), any(), any());
    }

    @Test
    void shouldSkipFollowNodeDeletionWhenUnfollowEventWasAlreadyProcessed() {
        var event = UserUnfollowedEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(UUID.randomUUID())
                .occurredAt(Instant.now())
                .followerUserId(UUID.randomUUID())
                .followedUserId(UUID.randomUUID())
                .build();
        when(processedEventsRepository.existsById(event.id())).thenReturn(true);

        followRabbitMQListener.onUserUnfollowed(event);

        verify(followNodeService, never()).deleteFollowRelationship(any(), any());
        verify(userStatsService, never()).decrementFollowCounters(any(), any());
        verify(processedEventsRepository, never()).insertIfAbsent(any(), any(), any());
    }
}
