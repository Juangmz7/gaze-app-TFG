package com.app.postcommandservice.comment.infrastructure.events;

import java.time.Instant;
import java.util.UUID;

import lombok.Builder;

import com.app.postcommandservice.shared.infrastructure.events.EventMessage;

@Builder
public record CommentDeletedEvent(
        UUID id,
        UUID correlationId,
        UUID commentId,
        UUID postId,
        UUID userId,
        Instant occurredAt
) implements EventMessage {
}
