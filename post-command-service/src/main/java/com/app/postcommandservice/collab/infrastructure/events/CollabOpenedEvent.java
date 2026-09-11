package com.app.postcommandservice.collab.infrastructure.events;

import java.time.Instant;
import java.util.Set;
import java.util.List;
import java.util.UUID;

import lombok.Builder;

import com.app.postcommandservice.collab.domain.model.valueobj.ColabStatus;
import com.app.postcommandservice.collab.domain.model.valueobj.CollabMemberRole;
import com.app.postcommandservice.collab.domain.model.valueobj.CollabMemberStatus;
import com.app.postcommandservice.post.domain.model.valueobj.PostType;
import com.app.postcommandservice.post.domain.model.PostMedia;
import com.app.postcommandservice.shared.infrastructure.events.EventMessage;

@Builder
public record CollabOpenedEvent(
        UUID id,
        UUID correlationId,
        Instant occurredAt,
        UUID collabId,
        String title,
        UUID createdBy,
        ColabStatus collabStatus,
        Instant collabCreatedAt,
        CollabMemberStatus creatorMemberStatus,
        CollabMemberRole creatorRole,
        Instant creatorMemberCreatedAt,
        UUID postId,
        UUID userId,
        UUID postCollabId,
        PostType postType,
        String description,
        Set<String> taggedUsers,
        Set<String> postTags,
        String postTitle,
        List<PostMedia> media,
        Instant postCreatedAt,
        Instant postUpdatedAt
) implements EventMessage {
}
