package com.app.postcommandservice.report.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.app.postcommandservice.report.domain.exception.CannotReportOwnContentException;
import com.app.postcommandservice.report.domain.model.valueobj.ReportStatus;

public class CommentReport {

    private final UUID id;
    private final UUID postId;
    private final UUID commentId;
    private final ReportClaim claim;
    private final CommentReportSnapshot snapshot;
    private final ReportStatus status;
    private final Instant createdAt;

    public CommentReport(
            UUID id,
            UUID postId,
            UUID commentId,
            ReportClaim claim,
            CommentReportSnapshot snapshot,
            ReportStatus status,
            Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.postId = Objects.requireNonNull(postId, "postId must not be null");
        this.commentId = Objects.requireNonNull(commentId, "commentId must not be null");
        this.claim = Objects.requireNonNull(claim, "claim must not be null");
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    /** Opens a new report; nobody can report their own comment. */
    public static CommentReport open(
            UUID id,
            UUID postId,
            UUID commentId,
            ReportClaim claim,
            CommentReportSnapshot snapshot,
            Instant createdAt) {
        Objects.requireNonNull(claim, "claim must not be null");
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        if (claim.reporterUserId().equals(snapshot.authorUserId())) {
            throw new CannotReportOwnContentException();
        }
        return new CommentReport(id, postId, commentId, claim, snapshot, ReportStatus.OPEN, createdAt);
    }

    public UUID getId() {
        return id;
    }

    public UUID getPostId() {
        return postId;
    }

    public UUID getCommentId() {
        return commentId;
    }

    public ReportClaim getClaim() {
        return claim;
    }

    public CommentReportSnapshot getSnapshot() {
        return snapshot;
    }

    public ReportStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
