package com.app.socialservice.shared.infrastructure.rabbitmq.listener;

import java.time.Instant;
import java.util.UUID;

import com.app.socialservice.follow.application.service.FollowNodeService;
import com.app.socialservice.follow.infrastructure.events.UserFollowedEvent;
import com.app.socialservice.shared.infrastructure.entity.ProcessedEvent;
import com.app.socialservice.shared.infrastructure.rabbitmq.config.RabbitMQProperties;
import com.app.socialservice.shared.infrastructure.repository.ProcessedEventsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FollowRabbitMQListenerTest {

    @Mock
    private FollowNodeService followNodeService;

    @Mock
    private ProcessedEventsRepository processedEventsRepository;

    @Spy
    private RabbitMQProperties rabbitMQProperties = new RabbitMQProperties();

    @InjectMocks
    private FollowRabbitMQListener followRabbitMQListener;

    @BeforeEach
    void setUp() {
        rabbitMQProperties.getQueue().getUser().getFollow().setCreated("q.social-service.user.follow.created");
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

        verify(followNodeService).createFollowRelationship(followerId, followedId);
        var processedEventCaptor = ArgumentCaptor.forClass(ProcessedEvent.class);
        verify(processedEventsRepository).save(processedEventCaptor.capture());
        assertThat(processedEventCaptor.getValue().getId()).isEqualTo(event.id());
        assertThat(processedEventCaptor.getValue().getCorrelationId()).isEqualTo(event.correlationId());
        assertThat(processedEventCaptor.getValue().getEventType()).isEqualTo(UserFollowedEvent.class.getSimpleName());
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
        verify(processedEventsRepository, never()).save(any(ProcessedEvent.class));
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
        verify(processedEventsRepository, never()).save(any(ProcessedEvent.class));
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

        verify(processedEventsRepository, never()).save(any(ProcessedEvent.class));
    }
}
