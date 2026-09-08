package com.app.postcommandservice.post.infrastructure.events;

import java.time.Instant;
import java.util.UUID;

import lombok.Builder;

import com.app.postcommandservice.shared.infrastructure.events.EventMessage;

@Builder
public record PostDeletedEvent(
        UUID id,
        UUID correlationId,
        UUID postId,
        UUID userId,
        Instant occurredAt
) implements EventMessage {
}
