package com.app.postcommandservice.report.application.dto;

import java.time.Instant;
import java.util.UUID;

import com.app.postcommandservice.report.domain.model.valueobj.ReportReason;
import com.app.postcommandservice.report.domain.model.valueobj.ReportStatus;

public record PostReportItemResponse(
        UUID reportId,
        PostReportTargetResponse target,
        UUID reporterUserId,
        ReportReason reason,
        String details,
        PostReportSnapshotResponse snapshot,
        ReportStatus status,
        Instant createdAt) {
}
