package com.app.socialservice.block.application.service;

import com.app.socialservice.block.domain.events.UserBlockedDomainEvent;
import com.app.socialservice.block.domain.model.Block;
import com.app.socialservice.block.infrastructure.events.UserBlockedEvent;
import com.app.socialservice.block.infrastructure.mapper.BlockEventMapper;
import com.app.socialservice.shared.infrastructure.entity.OutboxEvent;
import com.app.socialservice.shared.infrastructure.enums.EventStatus;
import com.app.socialservice.shared.infrastructure.mapper.JsonMapper;
import com.app.socialservice.shared.infrastructure.repository.OutboxEventRepository;
import com.app.socialservice.user.domain.model.valueobj.UserId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BlockEventServiceTest {

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private BlockEventMapper blockEventMapper;

    @Mock
    private JsonMapper jsonMapper;

    @InjectMocks
    private BlockEventService blockEventService;

    @Test
    void shouldStoreBlockOutboxEventInWaitingState() {
        var blockerId = UUID.randomUUID();
        var blockedId = UUID.randomUUID();
        var block = new Block(new UserId(blockerId), new UserId(blockedId), Instant.now());
        var event = UserBlockedEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(UUID.randomUUID())
                .occurredAt(Instant.now())
                .blockerUserId(blockerId)
                .blockedUserId(blockedId)
                .build();

        when(blockEventMapper.toUserBlockedEvent(any(), any(), any(), any())).thenReturn(event);
        when(jsonMapper.toJson(event)).thenReturn("{\"type\":\"blocked\"}");
        when(outboxEventRepository.save(any(OutboxEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UUID outboxEventId = blockEventService.storePendingBlockEvent(block);

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(captor.capture());
        assertThat(outboxEventId).isEqualTo(captor.getValue().getId());
        assertThat(captor.getValue().getStatus()).isEqualTo(EventStatus.WAITING);
        assertThat(captor.getValue().getEventType()).isEqualTo(UserBlockedEvent.class.getSimpleName());
    }

    @Test
    void shouldMarkWaitingOutboxAsPendingAndPublishDomainEvent() {
        var blockerId = UUID.randomUUID();
        var blockedId = UUID.randomUUID();
        var outboxEventId = UUID.randomUUID();
        var block = new Block(new UserId(blockerId), new UserId(blockedId), Instant.now());
        var outboxEvent = OutboxEvent.builder()
                .id(outboxEventId)
                .correlationId(UUID.randomUUID())
                .payload("{}")
                .eventType(UserBlockedEvent.class.getSimpleName())
                .status(EventStatus.WAITING)
                .createdAt(Instant.now())
                .build();

        when(outboxEventRepository.findById(outboxEventId)).thenReturn(Optional.of(outboxEvent));

        blockEventService.enqueueAndPublishPendingBlockEvent(outboxEventId, block);

        assertThat(outboxEvent.getStatus()).isEqualTo(EventStatus.PENDING);
        verify(outboxEventRepository).save(outboxEvent);
        verify(eventPublisher).publishEvent(org.mockito.ArgumentMatchers.isA(UserBlockedDomainEvent.class));
    }

    @Test
    void shouldFindWaitingOutboxEventIdForBlock() {
        var blockerId = UUID.randomUUID();
        var blockedId = UUID.randomUUID();
        var outboxEventId = UUID.randomUUID();
        var block = new Block(new UserId(blockerId), new UserId(blockedId), Instant.now());
        var outboxEvent = OutboxEvent.builder()
                .id(outboxEventId)
                .correlationId(UUID.randomUUID())
                .payload("{\"blockerUserId\":\"" + blockerId + "\",\"blockedUserId\":\"" + blockedId + "\"}")
                .eventType(UserBlockedEvent.class.getSimpleName())
                .status(EventStatus.WAITING)
                .createdAt(Instant.now())
                .build();
        var event = UserBlockedEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(UUID.randomUUID())
                .occurredAt(Instant.now())
                .blockerUserId(blockerId)
                .blockedUserId(blockedId)
                .build();

        when(outboxEventRepository.findByEventTypeAndStatusOrderByCreatedAtAsc(
                UserBlockedEvent.class.getSimpleName(),
                EventStatus.WAITING
        )).thenReturn(List.of(outboxEvent));
        when(jsonMapper.fromJson(outboxEvent.getPayload(), UserBlockedEvent.class)).thenReturn(event);

        var waitingOutboxEventId = blockEventService.findWaitingBlockEventId(block);

        assertThat(waitingOutboxEventId).isEqualTo(outboxEventId);
    }

    @Test
    void shouldFindWaitingOutboxEventIdUsingCorrectDirectionWhenOppositeDirectionExists() {
        var blockerId = UUID.randomUUID();
        var blockedId = UUID.randomUUID();
        var wrongOutboxEventId = UUID.randomUUID();
        var correctOutboxEventId = UUID.randomUUID();
        var block = new Block(new UserId(blockerId), new UserId(blockedId), Instant.now());
        var wrongOutboxEvent = OutboxEvent.builder()
                .id(wrongOutboxEventId)
                .correlationId(UUID.randomUUID())
                .payload("{\"direction\":\"wrong\"}")
                .eventType(UserBlockedEvent.class.getSimpleName())
                .status(EventStatus.WAITING)
                .createdAt(Instant.now())
                .build();
        var correctOutboxEvent = OutboxEvent.builder()
                .id(correctOutboxEventId)
                .correlationId(UUID.randomUUID())
                .payload("{\"direction\":\"correct\"}")
                .eventType(UserBlockedEvent.class.getSimpleName())
                .status(EventStatus.WAITING)
                .createdAt(Instant.now().plusSeconds(1))
                .build();
        var wrongDirectionEvent = UserBlockedEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(UUID.randomUUID())
                .occurredAt(Instant.now())
                .blockerUserId(blockedId)
                .blockedUserId(blockerId)
                .build();
        var correctDirectionEvent = UserBlockedEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(UUID.randomUUID())
                .occurredAt(Instant.now())
                .blockerUserId(blockerId)
                .blockedUserId(blockedId)
                .build();

        when(outboxEventRepository.findByEventTypeAndStatusOrderByCreatedAtAsc(
                UserBlockedEvent.class.getSimpleName(),
                EventStatus.WAITING
        )).thenReturn(List.of(wrongOutboxEvent, correctOutboxEvent));
        when(jsonMapper.fromJson(wrongOutboxEvent.getPayload(), UserBlockedEvent.class)).thenReturn(wrongDirectionEvent);
        when(jsonMapper.fromJson(correctOutboxEvent.getPayload(), UserBlockedEvent.class)).thenReturn(correctDirectionEvent);

        var waitingOutboxEventId = blockEventService.findWaitingBlockEventId(block);

        assertThat(waitingOutboxEventId).isEqualTo(correctOutboxEventId);
    }

    @Test
    void shouldThrowWhenOutboxEventDoesNotExist() {
        var block = new Block(new UserId(UUID.randomUUID()), new UserId(UUID.randomUUID()), Instant.now());
        var outboxEventId = UUID.randomUUID();

        when(outboxEventRepository.findById(outboxEventId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> blockEventService.enqueueAndPublishPendingBlockEvent(outboxEventId, block))
                .isInstanceOf(jakarta.persistence.EntityNotFoundException.class)
                .hasMessage("Outbox event not found: " + outboxEventId);
    }
}
