package com.app.socialservice.block.infrastructure.events;

import java.time.Instant;
import java.util.UUID;

import com.app.socialservice.shared.infrastructure.events.EventMessage;
import lombok.Builder;

@Builder
public record UserUnblockedEvent(
        UUID id,
        UUID correlationId,
        Instant occurredAt,
        UUID blockerUserId,
        UUID blockedUserId
) implements EventMessage {
}
