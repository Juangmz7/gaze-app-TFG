package com.app.postcommandservice.report.application.dto;

import java.util.UUID;

public record CommentReportTargetResponse(UUID postId, UUID commentId) {
}
