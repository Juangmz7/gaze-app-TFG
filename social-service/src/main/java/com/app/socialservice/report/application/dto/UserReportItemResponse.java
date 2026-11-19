package com.app.socialservice.report.application.dto;

import java.time.Instant;
import java.util.UUID;

import com.app.socialservice.report.domain.enums.ReportStatus;
import com.app.socialservice.report.domain.enums.UserReportReason;
import com.app.socialservice.shared.application.media.SignablePictureUrl;

/**
 * The picture to read-sign is the reported user's current one (reportedUser.profilePic).
 */
public record UserReportItemResponse(
        UUID reportId,
        UUID reporterUserId,
        ReportedUserResponse reportedUser,
        UserReportReason reason,
        String details,
        UserReportSnapshotResponse snapshot,
        ReportStatus status,
        Instant createdAt
) implements SignablePictureUrl<UserReportItemResponse> {

    @Override
    public String pictureUrlToSign() {
        return reportedUser.profilePic();
    }

    @Override
    public UserReportItemResponse withPictureUrl(String pictureUrl) {
        return new UserReportItemResponse(
                reportId,
                reporterUserId,
                new ReportedUserResponse(reportedUser.userId(), reportedUser.username(), pictureUrl),
                reason,
                details,
                snapshot,
                status,
                createdAt
        );
    }
}
