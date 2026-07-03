package com.app.socialservice.shared.infrastructure.rabbitmq.listener;

import java.time.Instant;
import java.util.UUID;

import com.app.socialservice.block.application.service.BlockNodeService;
import com.app.socialservice.block.infrastructure.events.UserBlockedEvent;
import com.app.socialservice.shared.infrastructure.rabbitmq.config.RabbitMQProperties;
import com.app.socialservice.shared.infrastructure.repository.ProcessedEventsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BlockRabbitMQListenerTest {

    @Mock
    private BlockNodeService blockNodeService;

    @Mock
    private ProcessedEventsRepository processedEventsRepository;

    @Spy
    private RabbitMQProperties rabbitMQProperties = new RabbitMQProperties();

    @InjectMocks
    private BlockRabbitMQListener blockRabbitMQListener;

    @BeforeEach
    void setUp() {
        rabbitMQProperties.getQueue().getUser().getBlock().setCreated("q.social-service.user.block.created");
    }

    @Test
    void shouldDelegateUserBlockedEventToBlockNodeServiceAndRecordProcessedEvent() {
        var blockerId = UUID.randomUUID();
        var blockedId = UUID.randomUUID();
        var event = UserBlockedEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(UUID.randomUUID())
                .occurredAt(Instant.now())
                .blockerUserId(blockerId)
                .blockedUserId(blockedId)
                .build();
        when(processedEventsRepository.existsById(event.id())).thenReturn(false);
        when(processedEventsRepository.existsByCorrelationId(event.correlationId())).thenReturn(false);

        blockRabbitMQListener.onUserBlocked(event);

        verify(blockNodeService).deleteBidirectionalFollowRelationship(blockerId, blockedId);
        verify(processedEventsRepository).insertIfAbsent(
                event.id(),
                event.correlationId(),
                UserBlockedEvent.class.getSimpleName()
        );
    }

    @Test
    void shouldSkipBlockNodeCleanupWhenBlockEventIdWasAlreadyProcessed() {
        var event = UserBlockedEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(UUID.randomUUID())
                .occurredAt(Instant.now())
                .blockerUserId(UUID.randomUUID())
                .blockedUserId(UUID.randomUUID())
                .build();
        when(processedEventsRepository.existsById(event.id())).thenReturn(true);

        blockRabbitMQListener.onUserBlocked(event);

        verify(blockNodeService, never()).deleteBidirectionalFollowRelationship(any(), any());
        verify(processedEventsRepository, never()).insertIfAbsent(any(), any(), any());
    }

    @Test
    void shouldRejectInvalidUserBlockedEventPayloadToDlq() {
        var userId = UUID.randomUUID();
        var event = UserBlockedEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(UUID.randomUUID())
                .occurredAt(Instant.now())
                .blockerUserId(userId)
                .blockedUserId(userId)
                .build();

        assertThatThrownBy(() -> blockRabbitMQListener.onUserBlocked(event))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("event blocker and blocked users must be different");

        verify(blockNodeService, never()).deleteBidirectionalFollowRelationship(any(), any());
        verify(processedEventsRepository, never()).insertIfAbsent(any(), any(), any());
    }

    @Test
    void shouldRethrowWhenBlockNodeCleanupFails() {
        var event = UserBlockedEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(UUID.randomUUID())
                .occurredAt(Instant.now())
                .blockerUserId(UUID.randomUUID())
                .blockedUserId(UUID.randomUUID())
                .build();
        when(processedEventsRepository.existsById(event.id())).thenReturn(false);
        when(processedEventsRepository.existsByCorrelationId(event.correlationId())).thenReturn(false);

        doThrow(new RuntimeException("neo4j cleanup failed"))
                .when(blockNodeService)
                .deleteBidirectionalFollowRelationship(event.blockerUserId(), event.blockedUserId());

        assertThatThrownBy(() -> blockRabbitMQListener.onUserBlocked(event))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("neo4j cleanup failed");

        verify(processedEventsRepository, never()).insertIfAbsent(any(), any(), any());
    }
}
