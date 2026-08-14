package com.app.postcommandservice.post.infrastructure.events;

import java.time.Instant;
import java.util.UUID;

import lombok.Builder;

@Builder
public record PostDeletedEvent(
        UUID postId,
        Instant occurredAt
) {
}
