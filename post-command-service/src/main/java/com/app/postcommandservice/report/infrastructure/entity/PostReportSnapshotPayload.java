package com.app.postcommandservice.report.infrastructure.entity;

import java.util.List;
import java.util.UUID;

/** jsonb shape of {@code post_reports.snapshot}. */
public record PostReportSnapshotPayload(String description, List<String> mediaUrls, UUID authorUserId) {
}
