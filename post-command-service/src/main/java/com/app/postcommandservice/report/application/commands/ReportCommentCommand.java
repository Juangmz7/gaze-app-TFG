package com.app.postcommandservice.report.application.commands;

import java.util.UUID;

import com.app.postcommandservice.report.domain.model.valueobj.ReportReason;

public record ReportCommentCommand(
        UUID postId,
        UUID commentId,
        UUID reporterUserId,
        ReportReason reason,
        String details) {
}
