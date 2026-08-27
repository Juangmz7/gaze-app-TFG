package com.app.postcommandservice.commentlike.application.commands;

import java.time.Instant;
import java.util.UUID;

import com.app.postcommandservice.commentlike.domain.model.CommentLikeSource;
import com.app.postcommandservice.shared.infrastructure.events.EventMessage;

public record ValidateCommentLikeCommand(
        UUID id,
        UUID correlationId,
        Instant occurredAt,
        UUID postId,
        UUID commentId,
        UUID userId,
        CommentLikeSource source,
        int feedPosition
) implements EventMessage {
}
