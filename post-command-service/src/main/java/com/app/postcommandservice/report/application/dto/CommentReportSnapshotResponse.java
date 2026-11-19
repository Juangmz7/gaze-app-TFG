package com.app.postcommandservice.report.application.dto;

import java.util.UUID;

public record CommentReportSnapshotResponse(String content, UUID authorUserId, UUID postId) {
}
