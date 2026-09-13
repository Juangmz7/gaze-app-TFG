package com.app.postcommandservice.post.infrastructure.mapper;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.app.postcommandservice.post.domain.model.Post;
import com.app.postcommandservice.post.infrastructure.events.PostCreatedEvent;
import com.app.postcommandservice.post.infrastructure.events.PostDeletedEvent;
import com.app.postcommandservice.post.infrastructure.events.PostUpdatedEvent;

@Component
public class PostEventMapper {

    public PostCreatedEvent toPostCreatedEvent(UUID eventId, UUID correlationId, Post post, Instant occurredAt) {
        return PostCreatedEvent.builder()
                .id(eventId)
                .correlationId(correlationId)
                .occurredAt(occurredAt)
                .postId(post.getId().value())
                .userId(post.getUserId().value())
                .collabId(post.getCollabId())
                .postType(post.getPostType())
                .description(post.getDescription().value())
                .taggedUsers(post.getTaggedUsers().value())
                .postTags(post.getTags().value())
                .createdAt(post.getCreatedAt())
                .updatedAt(post.getUpdatedAt())
                .build();
    }

    public PostUpdatedEvent toPostUpdatedEvent(UUID eventId, UUID correlationId, Post post, Instant occurredAt) {
        return PostUpdatedEvent.builder()
                .id(eventId)
                .correlationId(correlationId)
                .occurredAt(occurredAt)
                .postId(post.getId().value())
                .userId(post.getUserId().value())
                .collabId(post.getCollabId())
                .postType(post.getPostType())
                .description(post.getDescription().value())
                .taggedUsers(post.getTaggedUsers().value())
                .postTags(post.getTags().value())
                .createdAt(post.getCreatedAt())
                .updatedAt(post.getUpdatedAt())
                .build();
    }

    public PostDeletedEvent toPostDeletedEvent(
            UUID eventId,
            UUID correlationId,
            UUID postId,
            UUID userId,
            Instant occurredAt) {
        return PostDeletedEvent.builder()
                .id(eventId)
                .correlationId(correlationId)
                .postId(postId)
                .userId(userId)
                .occurredAt(occurredAt)
                .build();
    }
}
