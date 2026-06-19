package com.app.socialservice.user.application.commands;

import java.time.Instant;
import java.util.UUID;

public record DeleteUserCommand(
        UUID id,
        UUID correlationId,
        UUID userId,
        Instant occurredOn,
        String eventType
) {
}
