package com.app.postcommandservice.like.infrastructure.mapper;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.app.postcommandservice.like.domain.model.PostLike;
import com.app.postcommandservice.like.infrastructure.events.PostLikeCreatedEvent;
import com.app.postcommandservice.like.infrastructure.events.PostLikeDeletedEvent;

@Component
public class PostLikeEventMapper {

    public PostLikeCreatedEvent toPostLikeCreatedEvent(
            UUID eventId,
            UUID correlationId,
            PostLike postLike,
            Instant occurredAt) {
        return PostLikeCreatedEvent.builder()
                .id(eventId)
                .correlationId(correlationId)
                .occurredAt(occurredAt)
                .postId(postLike.getPostId().value())
                .userId(postLike.getUserId().value())
                .createdAt(postLike.getCreatedAt())
                .build();
    }

    public PostLikeDeletedEvent toPostLikeDeletedEvent(
            UUID eventId,
            UUID correlationId,
            UUID postId,
            UUID userId,
            Instant occurredAt) {
        return PostLikeDeletedEvent.builder()
                .id(eventId)
                .correlationId(correlationId)
                .occurredAt(occurredAt)
                .postId(postId)
                .userId(userId)
                .build();
    }
}
