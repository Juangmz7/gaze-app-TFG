package com.app.postcommandservice.post.infrastructure.events;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import lombok.Builder;

import com.app.postcommandservice.shared.infrastructure.events.EventMessage;

@Builder
public record PostUpdatedEvent(
        UUID id,
        UUID correlationId,
        Instant occurredAt,
        UUID postId,
        UUID userId,
        String description,
        Set<String> taggedUsers,
        Set<String> postTags,
        Instant createdAt,
        Instant updatedAt
) implements EventMessage {
}
