package com.app.postcommandservice.commentlike.infrastructure.events;

import java.time.Instant;
import java.util.UUID;

import lombok.Builder;

import com.app.postcommandservice.commentlike.domain.model.CommentLikeSource;
import com.app.postcommandservice.shared.infrastructure.events.EventMessage;

@Builder
public record PostCommentLikeDeletedEvent(
        UUID id,
        UUID correlationId,
        Instant occurredAt,
        UUID commentId,
        UUID userId,
        CommentLikeSource source,
        int feedPosition
) implements EventMessage {
}
