package com.app.socialservice.report.infrastructure.entity;

import java.util.Map;

/**
 * jsonb shape of user_reports.snapshot.
 */
public record UserReportSnapshotPayload(
        String username,
        String description,
        Map<String, String> socialMedia,
        String profilePic
) {
}
