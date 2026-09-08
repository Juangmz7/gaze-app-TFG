package com.app.postcommandservice.comment.infrastructure.mapper;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.app.postcommandservice.comment.domain.model.Comment;
import com.app.postcommandservice.comment.infrastructure.events.CommentCreatedEvent;
import com.app.postcommandservice.comment.infrastructure.events.CommentDeletedEvent;
import com.app.postcommandservice.comment.infrastructure.events.CommentUpdatedEvent;

@Component
public class CommentEventMapper {

    public CommentCreatedEvent toCommentCreatedEvent(
            UUID eventId,
            UUID correlationId,
            Comment comment,
            Instant occurredAt) {
        return CommentCreatedEvent.builder()
                .id(eventId)
                .correlationId(correlationId)
                .occurredAt(occurredAt)
                .commentId(comment.getId().value())
                .postId(comment.getPostId().value())
                .userId(comment.getUserId().value())
                .content(comment.getContent().value())
                .replyTo(comment.getReplyTo())
                .createdAt(comment.getCreatedAt())
                .updatedAt(comment.getUpdatedAt())
                .build();
    }

    public CommentUpdatedEvent toCommentUpdatedEvent(Comment comment) {
        return CommentUpdatedEvent.builder()
                .commentId(comment.getId().value())
                .postId(comment.getPostId().value())
                .userId(comment.getUserId().value())
                .content(comment.getContent().value())
                .replyTo(comment.getReplyTo())
                .createdAt(comment.getCreatedAt())
                .updatedAt(comment.getUpdatedAt())
                .build();
    }

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
