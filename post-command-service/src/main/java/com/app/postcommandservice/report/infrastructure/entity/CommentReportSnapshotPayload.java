package com.app.postcommandservice.report.infrastructure.entity;

import java.util.UUID;

/** jsonb shape of {@code comment_reports.snapshot}. */
public record CommentReportSnapshotPayload(String content, UUID authorUserId, UUID postId) {
}
