package com.app.postcommandservice.report.application.commands;

import java.util.UUID;

import com.app.postcommandservice.report.domain.model.valueobj.ReportReason;

public record ReportPostCommand(UUID postId, UUID reporterUserId, ReportReason reason, String details) {
}
