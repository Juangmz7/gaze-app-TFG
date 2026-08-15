package com.app.postcommandservice.comment.application.dto;

import java.time.Instant;
import java.util.UUID;

public record CommentResponse(
        UUID commentId,
        UUID postId,
        UUID userId,
        String content,
        UUID replyTo,
        Instant updatedAt,
        Instant createdAt
) {
}
