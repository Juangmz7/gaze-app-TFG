package com.app.postcommandservice.comment.infrastructure.events;

import java.time.Instant;
import java.util.UUID;

import lombok.Builder;

import com.app.postcommandservice.shared.infrastructure.events.EventMessage;

@Builder
public record CommentCreatedEvent(
        UUID id,
        UUID correlationId,
        Instant occurredAt,
        UUID commentId,
        UUID postId,
        UUID userId,
        String content,
        UUID replyTo,
        Instant createdAt,
        Instant updatedAt
) implements EventMessage {
}
