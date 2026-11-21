package com.app.socialservice.report.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.app.socialservice.report.domain.enums.ReportStatus;
import com.app.socialservice.report.domain.enums.UserReportReason;
import com.app.socialservice.report.domain.exception.CannotReportSelfException;
import com.app.socialservice.report.domain.exception.InvalidReportDetailsException;
import com.app.socialservice.report.domain.model.valueobj.ReportDetails;

public class UserReport {

    private final UUID id;
    private final UUID reporterUserId;
    private final UUID reportedUserId;
    private final UserReportReason reason;
    private final ReportDetails details;
    private final UserReportSnapshot snapshot;
    private final ReportStatus status;
    private final Instant createdAt;

    public UserReport(
            UUID id,
            UUID reporterUserId,
            UUID reportedUserId,
            UserReportReason reason,
            ReportDetails details,
            UserReportSnapshot snapshot,
            ReportStatus status,
            Instant createdAt
    ) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.reporterUserId = Objects.requireNonNull(reporterUserId, "reporterUserId must not be null");
        this.reportedUserId = Objects.requireNonNull(reportedUserId, "reportedUserId must not be null");
        this.reason = Objects.requireNonNull(reason, "reason must not be null");
        this.details = details == null ? new ReportDetails(null) : details;
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    /**
     * Opens a new report: details are required when the reason is OTHER and nobody can report
     * themselves.
     */
    public static UserReport open(
            UUID id,
            UUID reporterUserId,
            UUID reportedUserId,
            UserReportReason reason,
            ReportDetails details,
            UserReportSnapshot snapshot,
            Instant createdAt
    ) {
        assertValidClaim(reporterUserId, reportedUserId, reason, details);
        return new UserReport(
                id, reporterUserId, reportedUserId, reason, details, snapshot, ReportStatus.OPEN, createdAt);
    }

    /**
     * The checks that need no stored data, so a use case can reject a bad report before any query.
     */
    public static void assertValidClaim(
            UUID reporterUserId,
            UUID reportedUserId,
            UserReportReason reason,
            ReportDetails details
    ) {
        Objects.requireNonNull(reporterUserId, "reporterUserId must not be null");
        Objects.requireNonNull(reportedUserId, "reportedUserId must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        if (reason == UserReportReason.OTHER && (details == null || details.isEmpty())) {
            throw new InvalidReportDetailsException("details are required when the reason is OTHER");
        }
        if (reporterUserId.equals(reportedUserId)) {
            throw new CannotReportSelfException();
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getReporterUserId() {
        return reporterUserId;
    }

    public UUID getReportedUserId() {
        return reportedUserId;
    }

    public UserReportReason getReason() {
        return reason;
    }

    public ReportDetails getDetails() {
        return details;
    }

    public UserReportSnapshot getSnapshot() {
        return snapshot;
    }

    public ReportStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
