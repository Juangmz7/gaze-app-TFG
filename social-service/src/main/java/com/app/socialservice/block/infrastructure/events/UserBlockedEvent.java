package com.app.socialservice.block.infrastructure.events;

import com.app.socialservice.shared.infrastructure.events.EventMessage;
import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

@Builder
public record UserBlockedEvent(
        UUID id,
        UUID correlationId,
        Instant occurredAt,
        UUID blockerUserId,
        UUID blockedUserId
) implements EventMessage {
}
