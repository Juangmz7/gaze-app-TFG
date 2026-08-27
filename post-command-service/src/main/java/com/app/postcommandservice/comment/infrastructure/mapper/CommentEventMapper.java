package com.app.postcommandservice.comment.infrastructure.mapper;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.app.postcommandservice.comment.infrastructure.events.CommentDeletedEvent;

@Component
public class CommentEventMapper {

    public CommentDeletedEvent toCommentDeletedEvent(
            UUID eventId,
            UUID correlationId,
            UUID commentId,
            UUID postId,
            UUID userId,
            Instant occurredAt) {
        return CommentDeletedEvent.builder()
                .id(eventId)
                .correlationId(correlationId)
                .commentId(commentId)
                .postId(postId)
                .userId(userId)
                .occurredAt(occurredAt)
                .build();
    }
}
