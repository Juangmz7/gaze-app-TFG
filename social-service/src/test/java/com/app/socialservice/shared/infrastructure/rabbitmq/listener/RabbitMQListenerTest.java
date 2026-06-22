package com.app.socialservice.shared.infrastructure.rabbitmq.listener;

import java.time.Instant;
import java.util.UUID;

import com.app.socialservice.block.application.service.BlockNodeService;
import com.app.socialservice.block.infrastructure.events.UserBlockedEvent;
import com.app.socialservice.shared.infrastructure.entity.ProcessedEvent;
import com.app.socialservice.shared.infrastructure.repository.ProcessedEventsRepository;
import com.app.socialservice.user.application.service.UserNodeService;
import com.app.socialservice.user.application.service.UserService;
import com.app.socialservice.user.infrastructure.mapper.UserRegisterCommandMapper;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class RabbitMQListenerTest {

    @Mock
    private UserService userService;

    @Mock
    private UserRegisterCommandMapper userRegisterCommandMapper;

    @Mock
    private UserNodeService userNodeService;

    @Mock
    private BlockNodeService blockNodeService;

    @Mock
    private ProcessedEventsRepository processedEventsRepository;

    @InjectMocks
    private RabbitMQListener rabbitMQListener;

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

        rabbitMQListener.onUserBlocked(event);

        verify(blockNodeService).deleteBidirectionalFollowRelationship(blockerId, blockedId);
        ArgumentCaptor<ProcessedEvent> processedEventCaptor = ArgumentCaptor.forClass(ProcessedEvent.class);
        verify(processedEventsRepository).save(processedEventCaptor.capture());
        assertThat(processedEventCaptor.getValue().getId()).isEqualTo(event.id());
        assertThat(processedEventCaptor.getValue().getCorrelationId()).isEqualTo(event.correlationId());
        assertThat(processedEventCaptor.getValue().getEventType()).isEqualTo(UserBlockedEvent.class.getSimpleName());
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

        rabbitMQListener.onUserBlocked(event);

        verify(blockNodeService, never()).deleteBidirectionalFollowRelationship(any(), any());
        verify(processedEventsRepository, never()).save(any(ProcessedEvent.class));
    }

    @Test
    void shouldSkipBlockNodeCleanupWhenBlockCorrelationIdWasAlreadyProcessed() {
        var event = UserBlockedEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(UUID.randomUUID())
                .occurredAt(Instant.now())
                .blockerUserId(UUID.randomUUID())
                .blockedUserId(UUID.randomUUID())
                .build();
        when(processedEventsRepository.existsById(event.id())).thenReturn(false);
        when(processedEventsRepository.existsByCorrelationId(event.correlationId())).thenReturn(true);

        rabbitMQListener.onUserBlocked(event);

        verify(blockNodeService, never()).deleteBidirectionalFollowRelationship(any(), any());
        verify(processedEventsRepository, never()).save(any(ProcessedEvent.class));
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

        assertThatThrownBy(() -> rabbitMQListener.onUserBlocked(event))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("neo4j cleanup failed");

        verify(processedEventsRepository, never()).save(any(ProcessedEvent.class));
    }
}
