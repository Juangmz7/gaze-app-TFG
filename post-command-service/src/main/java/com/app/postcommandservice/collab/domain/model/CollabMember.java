package com.app.postcommandservice.collab.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.app.postcommandservice.collab.domain.exception.CollabJoinRequestNotPendingException;
import com.app.postcommandservice.collab.domain.model.valueobj.CollabMemberRole;
import com.app.postcommandservice.collab.domain.model.valueobj.CollabMemberStatus;
import com.app.postcommandservice.shared.domain.model.user.valueobj.UserId;

public class CollabMember {

    private final UUID collabId;
    private final UserId userId;
    private final CollabMemberStatus collabMemberStatus;
    private final CollabMemberRole role;
    private final Instant createdAt;

    public CollabMember(
            UUID collabId,
            UserId userId,
            CollabMemberStatus collabMemberStatus,
            CollabMemberRole role,
            Instant createdAt) {
        this.collabId = Objects.requireNonNull(collabId, "collabId must not be null");
        this.userId = Objects.requireNonNull(userId, "userId must not be null");
        this.collabMemberStatus = Objects.requireNonNull(collabMemberStatus, "collabMemberStatus must not be null");
        this.role = Objects.requireNonNull(role, "role must not be null");
        this.createdAt = createdAt;
    }

    public static CollabMember createCreatorAdmin(UUID collabId, UserId userId) {
        return new CollabMember(collabId, userId, CollabMemberStatus.ACCEPTED, CollabMemberRole.ADMIN, null);
    }

    public boolean isAcceptedAdmin() {
        return collabMemberStatus == CollabMemberStatus.ACCEPTED && role == CollabMemberRole.ADMIN;
    }

    public CollabMember accept() {
        if (collabMemberStatus != CollabMemberStatus.PENDING) {
            throw new CollabJoinRequestNotPendingException(collabId, userId.value(), collabMemberStatus);
        }

        return new CollabMember(collabId, userId, CollabMemberStatus.ACCEPTED, role, createdAt);
    }

    public CollabMember reject() {
        if (collabMemberStatus != CollabMemberStatus.PENDING) {
            throw new CollabJoinRequestNotPendingException(collabId, userId.value(), collabMemberStatus, "decline");
        }

        return new CollabMember(collabId, userId, CollabMemberStatus.REJECTED, role, createdAt);
    }

    public CollabMember cancelRequest() {
        if (collabMemberStatus != CollabMemberStatus.PENDING) {
            throw new CollabJoinRequestNotPendingException(collabId, userId.value(), collabMemberStatus, "cancel");
        }

        return new CollabMember(collabId, userId, CollabMemberStatus.DELETED, role, createdAt);
    }
  
    public static CollabMember createPendingMember(UUID collabId, UserId userId) {
        return new CollabMember(collabId, userId, CollabMemberStatus.PENDING, CollabMemberRole.MEMBER, null);
    }

    public UUID getCollabId() {
        return collabId;
    }

    public UserId getUserId() {
        return userId;
    }

    public CollabMemberStatus getCollabMemberStatus() {
        return collabMemberStatus;
    }

    public CollabMemberRole getRole() {
        return role;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
