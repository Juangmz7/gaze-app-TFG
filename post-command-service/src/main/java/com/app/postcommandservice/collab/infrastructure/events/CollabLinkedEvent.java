package com.app.postcommandservice.collab.infrastructure.events;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import lombok.Builder;

import com.app.postcommandservice.post.domain.model.valueobj.PostType;

@Builder
public record CollabLinkedEvent(
        UUID id,
        UUID correlationId,
        Instant occurredAt,
        UUID postId,
        UUID userId,
        UUID collabId,
        PostType postType,
        String description,
        Set<String> taggedUsers,
        Set<String> postTags,
        Instant createdAt,
        Instant updatedAt
) {
}
