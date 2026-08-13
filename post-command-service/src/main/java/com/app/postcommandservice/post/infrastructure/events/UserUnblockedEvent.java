package com.app.postcommandservice.post.infrastructure.events;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record UserUnblockedEvent(
        UUID id,
        UUID correlationId,
        Instant occurredAt,
        UUID blockerUserId,
        UUID blockedUserId
) {
}
