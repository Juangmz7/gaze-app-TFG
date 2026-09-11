package com.app.postcommandservice.collab.infrastructure.events;

import java.time.Instant;
import java.util.Set;
import java.util.List;
import java.util.UUID;

import lombok.Builder;

import com.app.postcommandservice.post.domain.model.valueobj.PostType;
import com.app.postcommandservice.post.domain.model.PostMedia;

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
        String title,
        List<PostMedia> media,
        Instant createdAt,
        Instant updatedAt
) {
}
