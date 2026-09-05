package com.app.postcommandservice.comment.infrastructure.events;

import java.time.Instant;
import java.util.UUID;

import lombok.Builder;

@Builder
public record CommentUpdatedEvent(
        UUID commentId,
        UUID postId,
        UUID userId,
        String content,
        UUID replyTo,
        Instant createdAt,
        Instant updatedAt
) {
}
