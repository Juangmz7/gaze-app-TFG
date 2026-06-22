package com.app.socialservice.block.application.dto;

import java.time.Instant;
import java.util.UUID;

public record BlockResponse(
        UUID blockerId,
        UUID blockedId,
        Instant createdAt
) {
}
