package com.app.socialservice.user.application.commands;

import java.time.Instant;
import java.util.UUID;

public record UserRegisterCommand(
        UUID id,
        UUID correlationId,
        UUID userId,
        String username,
        String email,
        Instant occurredOn
) {
}
