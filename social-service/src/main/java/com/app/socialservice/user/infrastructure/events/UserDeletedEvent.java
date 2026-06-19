package com.app.socialservice.user.infrastructure.events;

import com.app.socialservice.shared.infrastructure.events.EventMessage;
import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

@Builder
public record UserDeletedEvent(
        UUID id,
        UUID correlationId,
        Instant occurredAt,

        UUID userId
) implements EventMessage {}
