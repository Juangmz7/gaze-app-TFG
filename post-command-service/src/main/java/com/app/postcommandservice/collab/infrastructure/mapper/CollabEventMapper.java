package com.app.postcommandservice.collab.infrastructure.mapper;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.app.postcommandservice.collab.domain.model.Collab;
import com.app.postcommandservice.collab.domain.model.CollabMember;
import com.app.postcommandservice.collab.infrastructure.events.CollabDeletedEvent;
import com.app.postcommandservice.collab.infrastructure.events.CollabClosedEvent;
import com.app.postcommandservice.collab.infrastructure.events.CollabJoinRequestAcceptedEvent;
import com.app.postcommandservice.collab.infrastructure.events.CollabJoinRequestCreatedEvent;
import com.app.postcommandservice.collab.infrastructure.events.CollabJoinRequestDeletedEvent;
import com.app.postcommandservice.collab.infrastructure.events.CollabJoinRequestDeclinedEvent;
import com.app.postcommandservice.collab.infrastructure.events.CollabLinkedEvent;
import com.app.postcommandservice.collab.infrastructure.events.CollabMemberBannedEvent;
import com.app.postcommandservice.collab.infrastructure.events.CollabMemberLeftEvent;
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

    public CollabDeletedEvent toCollabDeletedEvent(
            UUID eventId,
            UUID correlationId,
            UUID collabId,
            UUID actionedBy,
            Instant occurredAt) {
        return CollabDeletedEvent.builder()
                .id(eventId)
                .correlationId(correlationId)
                .collabId(collabId)
                .actionedBy(actionedBy)
                .occurredAt(occurredAt)
                .build();
    }
  
    public CollabMemberLeftEvent toCollabMemberLeftEvent(
            UUID eventId,
            UUID correlationId,
            CollabMember collabMember,
            Instant occurredAt) {

        return CollabMemberLeftEvent.builder()
                .id(eventId)
                .correlationId(correlationId)
                .occurredAt(occurredAt)
                .collabId(collabMember.getCollabId())
                .userId(collabMember.getUserId().value())
                .collabMemberStatus(collabMember.getCollabMemberStatus())
                .role(collabMember.getRole())
                .createdAt(collabMember.getCreatedAt())
                .build();
    }

    public CollabMemberBannedEvent toCollabMemberBannedEvent(
            UUID eventId,
            UUID correlationId,
            CollabMember collabMember,
            Instant occurredAt) {

        return CollabMemberBannedEvent.builder()
                .id(eventId)
                .correlationId(correlationId)
                .occurredAt(occurredAt)
                .collabId(collabMember.getCollabId())
                .userId(collabMember.getUserId().value())
                .collabMemberStatus(collabMember.getCollabMemberStatus())
                .role(collabMember.getRole())
                .memberCreatedAt(collabMember.getCreatedAt())
                .build();
    }

    public CollabJoinRequestDeclinedEvent toCollabJoinRequestDeclinedEvent(
            UUID eventId,
            UUID correlationId,
            UUID declinedBy,
            CollabMember collabMember,
            Instant occurredAt) {

        return CollabJoinRequestDeclinedEvent.builder()
                .id(eventId)
                .correlationId(correlationId)
                .occurredAt(occurredAt)
                .collabId(collabMember.getCollabId())
                .userId(collabMember.getUserId().value())
                .declinedBy(declinedBy)
                .collabMemberStatus(collabMember.getCollabMemberStatus())
                .role(collabMember.getRole())
                .memberCreatedAt(collabMember.getCreatedAt())
                .build();
    }

    public CollabJoinRequestAcceptedEvent toCollabJoinRequestAcceptedEvent(
            UUID eventId,
            UUID correlationId,
            UUID acceptedBy,
            CollabMember collabMember,
            Instant occurredAt) {

        return CollabJoinRequestAcceptedEvent.builder()
                .id(eventId)
                .correlationId(correlationId)
                .occurredAt(occurredAt)
                .collabId(collabMember.getCollabId())
                .userId(collabMember.getUserId().value())
                .acceptedBy(acceptedBy)
                .collabMemberStatus(collabMember.getCollabMemberStatus())
                .role(collabMember.getRole())
                .memberCreatedAt(collabMember.getCreatedAt())
                .build();
    }

    public CollabJoinRequestDeletedEvent toCollabJoinRequestDeletedEvent(
            UUID eventId,
            UUID correlationId,
            UUID deletedBy,
            CollabMember collabMember,
            Instant occurredAt) {

        return CollabJoinRequestDeletedEvent.builder()
                .id(eventId)
                .correlationId(correlationId)
                .occurredAt(occurredAt)
                .collabId(collabMember.getCollabId())
                .userId(collabMember.getUserId().value())
                .deletedBy(deletedBy)
                .collabMemberStatus(collabMember.getCollabMemberStatus())
                .role(collabMember.getRole())
                .memberCreatedAt(collabMember.getCreatedAt())
                .build();
    }

    public CollabJoinRequestCreatedEvent toCollabJoinRequestCreatedEvent(
            UUID eventId,
            UUID correlationId,
            CollabMember collabMember,
            Instant occurredAt) {

        return CollabJoinRequestCreatedEvent.builder()
                .id(eventId)
                .correlationId(correlationId)
                .occurredAt(occurredAt)
                .collabId(collabMember.getCollabId())
                .userId(collabMember.getUserId().value())
                .status(collabMember.getCollabMemberStatus())
                .role(collabMember.getRole())
                .createdAt(collabMember.getCreatedAt())
                .build();
    }

    public CollabLinkedEvent toCollabLinkedEvent(
            UUID eventId,
            UUID correlationId,
            Post post,
            Instant occurredAt) {

        return CollabLinkedEvent.builder()
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
