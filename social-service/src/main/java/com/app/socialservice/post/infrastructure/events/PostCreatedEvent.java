package com.app.socialservice.post.infrastructure.events;

import java.time.Instant;
import java.util.UUID;

import com.app.socialservice.shared.infrastructure.events.EventMessage;
import lombok.Builder;

@Builder
public record PostCreatedEvent(
        UUID id,
        UUID correlationId,
        Instant occurredAt,
        UUID postId,
        UUID userId
) implements EventMessage {
}
