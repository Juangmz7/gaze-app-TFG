package com.app.postcommandservice.collab.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.app.postcommandservice.collab.domain.model.valueobj.ColabStatus;
import com.app.postcommandservice.collab.domain.model.valueobj.CollabJoinInstructions;
import com.app.postcommandservice.collab.domain.model.valueobj.CollabTitle;
import com.app.postcommandservice.shared.domain.model.user.valueobj.UserId;

public class Collab {

    private final UUID id;
    private final CollabTitle title;
    private final UserId createdBy;
    private final ColabStatus collabStatus;
    private final Instant createdAt;
    private final CollabJoinInstructions joinInstructions;

    public Collab(UUID id, CollabTitle title, UserId createdBy, ColabStatus collabStatus, Instant createdAt) {
        this(id, title, createdBy, collabStatus, createdAt, null);
    }

    /**
     * {@code joinInstructions} is optional: {@code null} means the collab has no instructions.
     */
    public Collab(
            UUID id,
            CollabTitle title,
            UserId createdBy,
            ColabStatus collabStatus,
            Instant createdAt,
            CollabJoinInstructions joinInstructions) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.title = Objects.requireNonNull(title, "title must not be null");
        this.createdBy = Objects.requireNonNull(createdBy, "createdBy must not be null");
        this.collabStatus = Objects.requireNonNull(collabStatus, "collabStatus must not be null");
        this.createdAt = createdAt;
        this.joinInstructions = joinInstructions;
    }

    public static Collab open(UUID id, CollabTitle title, UserId createdBy) {
        return open(id, title, createdBy, null);
    }

    public static Collab open(UUID id, CollabTitle title, UserId createdBy, CollabJoinInstructions joinInstructions) {
        return new Collab(id, title, createdBy, ColabStatus.OPEN, null, joinInstructions);
    }

    public Collab close() {
        return new Collab(id, title, createdBy, ColabStatus.CLOSED, createdAt, joinInstructions);
    }

    /**
     * Replaces the join instructions; {@code null} clears them.
     */
    public Collab updateJoinInstructions(CollabJoinInstructions newJoinInstructions) {
        return new Collab(id, title, createdBy, collabStatus, createdAt, newJoinInstructions);
    }

    public UUID getId() {
        return id;
    }

    public CollabTitle getTitle() {
        return title;
    }

    public UserId getCreatedBy() {
        return createdBy;
    }

    public ColabStatus getCollabStatus() {
        return collabStatus;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public CollabJoinInstructions getJoinInstructions() {
        return joinInstructions;
    }

    public boolean isDeleted() {
        return collabStatus == ColabStatus.DELETED;
    }

    public Collab delete() {
        return new Collab(id, title, createdBy, ColabStatus.DELETED, createdAt, joinInstructions);
    }
}
