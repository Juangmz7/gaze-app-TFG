package com.app.socialservice.user.infrastructure.events;

import com.app.socialservice.shared.infrastructure.events.EventMessage;
import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

@Builder
public record UserRegisteredEvent (
        UUID id,
        UUID correlationId,
        Instant occurredAt,

        UUID userId,
        String username,
        String email
) implements EventMessage {}