package com.app.socialservice.block.application.service;

import com.app.socialservice.block.domain.events.UserBlockedDomainEvent;
import com.app.socialservice.block.domain.model.Block;
import com.app.socialservice.block.infrastructure.events.UserBlockedEvent;
import com.app.socialservice.block.infrastructure.mapper.BlockEventMapper;
import com.app.socialservice.shared.infrastructure.entity.OutboxEvent;
import com.app.socialservice.shared.infrastructure.enums.EventStatus;
import com.app.socialservice.shared.infrastructure.mapper.JsonMapper;
import com.app.socialservice.shared.infrastructure.repository.OutboxEventRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class BlockEventService {

    private final ApplicationEventPublisher eventPublisher;
    private final OutboxEventRepository outboxEventRepository;
    private final BlockEventMapper blockEventMapper;
    private final JsonMapper jsonMapper;

    @Transactional
    public UUID storePendingBlockEvent(Block block) {
        if (block == null) {
            throw new IllegalArgumentException("block must not be null");
        }

        var correlationId = UUID.randomUUID();
        var occurredOn = Instant.now();
        var blockedEvent = blockEventMapper.toUserBlockedEvent(
                UUID.randomUUID(),
                correlationId,
                block,
                occurredOn
        );

        var outboxEvent = saveOutboxEvent(correlationId, occurredOn, blockedEvent);

        log.info("Block event stored in WAITING state for blocker {} and blocked {} with outbox id {}",
                block.getBlockerId().value(),
                block.getBlockedId().value(),
                outboxEvent.getId());

        return outboxEvent.getId();
    }

    @Transactional(readOnly = true)
    public UUID findWaitingBlockEventId(Block block) {
        if (block == null) {
            throw new IllegalArgumentException("block must not be null");
        }

        return outboxEventRepository
                .findByEventTypeAndStatusOrderByCreatedAtAsc(
                        UserBlockedEvent.class.getSimpleName(),
                        EventStatus.WAITING
                )
                .stream()
                .filter(outboxEvent -> matchesWaitingBlockEvent(outboxEvent, block))
                .map(OutboxEvent::getId)
                .findFirst()
                .orElse(null);
    }

    @Transactional
    public void enqueueAndPublishPendingBlockEvent(UUID outboxEventId, Block block) {
        if (outboxEventId == null) {
            throw new IllegalArgumentException("outboxEventId must not be null");
        }
        if (block == null) {
            throw new IllegalArgumentException("block must not be null");
        }

        var occurredOn = Instant.now();
        var outboxEvent = outboxEventRepository.findById(outboxEventId)
                .orElseThrow(() -> new EntityNotFoundException("Outbox event not found: " + outboxEventId));
        outboxEvent.setStatus(EventStatus.PENDING);
        outboxEventRepository.save(outboxEvent);

        log.info("Block event enqueued for blocker {} and blocked {} with outbox id {}",
                block.getBlockerId().value(),
                block.getBlockedId().value(),
                outboxEvent.getId());

        eventPublisher.publishEvent(new UserBlockedDomainEvent(
                outboxEvent.getId(),
                block.getBlockerId().value(),
                block.getBlockedId().value(),
                occurredOn
        ));
    }

    private OutboxEvent saveOutboxEvent(UUID correlationId, Instant occurredOn, UserBlockedEvent blockedEvent) {
        var payload = jsonMapper.toJson(blockedEvent);
        return outboxEventRepository.save(
                OutboxEvent.builder()
                        .id(UUID.randomUUID())
                        .correlationId(correlationId)
                        .payload(payload)
                        .eventType(UserBlockedEvent.class.getSimpleName())
                        .status(EventStatus.WAITING)
                        .createdAt(occurredOn)
                        .build()
        );
    }

    private boolean matchesWaitingBlockEvent(OutboxEvent outboxEvent, Block block) {
        var blockedEvent = jsonMapper.fromJson(outboxEvent.getPayload(), UserBlockedEvent.class);
        return blockedEvent.blockerUserId().equals(block.getBlockerId().value())
                && blockedEvent.blockedUserId().equals(block.getBlockedId().value());
    }
}
