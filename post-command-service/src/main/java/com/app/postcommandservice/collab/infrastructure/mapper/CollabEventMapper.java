package com.app.postcommandservice.collab.infrastructure.mapper;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.app.postcommandservice.collab.domain.model.Collab;
import com.app.postcommandservice.collab.domain.model.CollabMember;
import com.app.postcommandservice.collab.infrastructure.events.CollabClosedEvent;
import com.app.postcommandservice.collab.infrastructure.events.CollabOpenedEvent;
import com.app.postcommandservice.post.domain.model.Post;

@Component
public class CollabEventMapper {

    public CollabOpenedEvent toCollabOpenedEvent(
            UUID eventId,
            UUID correlationId,
            Collab collab,
            CollabMember collabMember,
            Post post,
            Instant occurredAt) {
        return CollabOpenedEvent.builder()
                .id(eventId)
                .correlationId(correlationId)
                .occurredAt(occurredAt)
                .collabId(collab.getId())
                .title(collab.getTitle().value())
                .createdBy(collab.getCreatedBy().value())
                .collabStatus(collab.getCollabStatus())
                .collabCreatedAt(collab.getCreatedAt())
                .creatorMemberStatus(collabMember.getCollabMemberStatus())
                .creatorRole(collabMember.getRole())
                .creatorMemberCreatedAt(collabMember.getCreatedAt())
                .postId(post.getId().value())
                .userId(post.getUserId().value())
                .postCollabId(post.getCollabId())
                .postType(post.getPostType())
                .description(post.getDescription().value())
                .taggedUsers(post.getTaggedUsers().value())
                .postTags(post.getTags().value())
                .postCreatedAt(post.getCreatedAt())
                .postUpdatedAt(post.getUpdatedAt())
                .build();
    }

    public CollabClosedEvent toCollabClosedEvent(
            UUID eventId,
            UUID correlationId,
            Collab collab,
            UUID closedBy,
            Instant occurredAt) {
        return CollabClosedEvent.builder()
                .id(eventId)
                .correlationId(correlationId)
                .occurredAt(occurredAt)
                .collabId(collab.getId())
                .title(collab.getTitle().value())
                .createdBy(collab.getCreatedBy().value())
                .closedBy(closedBy)
                .collabStatus(collab.getCollabStatus())
                .collabCreatedAt(collab.getCreatedAt())
                .build();
    }
}
