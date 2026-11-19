package com.app.socialservice.report.testutil;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.app.socialservice.report.application.dto.UserReportEntry;
import com.app.socialservice.report.domain.enums.ReportStatus;
import com.app.socialservice.report.domain.enums.UserReportReason;
import com.app.socialservice.report.domain.model.UserReportSnapshot;

public final class UserReportMother {

    public static final String REPORTED_USERNAME = "reported-user";
    public static final String REPORTED_DESCRIPTION = "Bio of the reported user";
    public static final Map<String, String> REPORTED_SOCIAL_MEDIA = Map.of("instagram", "@reported");
    public static final String REPORTED_PICTURE = "https://cdn.example.com/avatars/reported.jpg";

    private UserReportMother() {
    }

    public static UserReportSnapshot snapshot() {
        return new UserReportSnapshot(REPORTED_USERNAME, REPORTED_DESCRIPTION, REPORTED_SOCIAL_MEDIA, REPORTED_PICTURE);
    }

    public static UserReportEntry openEntry(UUID reportId, Instant createdAt) {
        Objects.requireNonNull(reportId, "reportId must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");

        return new UserReportEntry(
                reportId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                REPORTED_USERNAME,
                REPORTED_PICTURE,
                UserReportReason.SPAM,
                null,
                snapshot(),
                ReportStatus.OPEN,
                createdAt
        );
    }
}
