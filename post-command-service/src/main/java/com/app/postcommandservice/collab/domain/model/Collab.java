package com.app.postcommandservice.collab.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.app.postcommandservice.collab.domain.model.valueobj.ColabStatus;
import com.app.postcommandservice.collab.domain.model.valueobj.CollabTitle;
import com.app.postcommandservice.shared.domain.model.user.valueobj.UserId;

public class Collab {

    private final UUID id;
    private final CollabTitle title;
    private final UserId createdBy;
    private final ColabStatus collabStatus;
    private final Instant createdAt;

    public Collab(UUID id, CollabTitle title, UserId createdBy, ColabStatus collabStatus, Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.title = Objects.requireNonNull(title, "title must not be null");
        this.createdBy = Objects.requireNonNull(createdBy, "createdBy must not be null");
        this.collabStatus = Objects.requireNonNull(collabStatus, "collabStatus must not be null");
        this.createdAt = createdAt;
    }

    public static Collab open(UUID id, CollabTitle title, UserId createdBy) {
        return new Collab(id, title, createdBy, ColabStatus.OPEN, null);
    }

    public Collab close() {
        return new Collab(id, title, createdBy, ColabStatus.CLOSED, createdAt);
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
}
