package com.app.postcommandservice.commentlike.infrastructure.mapper;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.app.postcommandservice.commentlike.domain.model.PostCommentLike;
import com.app.postcommandservice.commentlike.infrastructure.events.PostCommentLikeCreatedEvent;

@Component
public class PostCommentLikeEventMapper {

    public PostCommentLikeCreatedEvent toPostCommentLikeCreatedEvent(
            UUID eventId,
            UUID correlationId,
            UUID postId,
            PostCommentLike commentLike,
            Instant occurredAt) {
        return PostCommentLikeCreatedEvent.builder()
                .id(eventId)
                .correlationId(correlationId)
                .occurredAt(occurredAt)
                .postId(postId)
                .commentId(commentLike.getCommentId().value())
                .userId(commentLike.getUserId().value())
                .source(commentLike.getContext().source())
                .feedPosition(commentLike.getContext().feedPosition())
                .createdAt(commentLike.getCreatedAt())
                .build();
    }
}
