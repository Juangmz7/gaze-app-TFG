package com.app.socialservice.user.application.commands;

import java.time.Instant;
import java.util.UUID;

public record UpdateAuthUserInfoCommand(
        UUID id,
        UUID correlationId,
        UUID userId,
        String username,
        String email,
        Instant occurredOn,
        String eventType
) {
}
