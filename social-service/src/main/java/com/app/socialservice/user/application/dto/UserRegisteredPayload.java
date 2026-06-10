package com.app.socialservice.user.application.dto;

import java.time.Instant;
import java.util.UUID;

public record UserRegisteredPayload(
        UUID id,
        String username,
        String email,
        Instant occurredOn
) {
}
