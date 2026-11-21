package com.app.socialservice.report.application.dto;

import java.time.Instant;
import java.util.UUID;

import com.app.socialservice.report.domain.enums.ReportStatus;
import com.app.socialservice.report.domain.enums.UserReportReason;
import com.app.socialservice.report.domain.model.UserReportSnapshot;
import com.app.socialservice.shared.domain.pagination.KeysetCursor;

/**
 * One row of the admin list of user reports as stored, with the reported user's current username
 * and picture (bare URL). The list is ordered by (createdAt, reportId) DESC.
 */
public record UserReportEntry(
        UUID reportId,
        UUID reporterUserId,
        UUID reportedUserId,
        String reportedUsername,
        String reportedProfilePic,
        UserReportReason reason,
        String details,
        UserReportSnapshot snapshot,
        ReportStatus status,
        Instant createdAt
) {

    public KeysetCursor sortKey() {
        return new KeysetCursor(createdAt, reportId);
    }
}
