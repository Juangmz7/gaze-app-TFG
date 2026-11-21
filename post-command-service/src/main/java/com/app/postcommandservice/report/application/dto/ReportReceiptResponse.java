package com.app.postcommandservice.report.application.dto;

import java.time.Instant;
import java.util.UUID;

import com.app.postcommandservice.report.domain.model.valueobj.ReportStatus;

public record ReportReceiptResponse(UUID reportId, ReportStatus status, Instant createdAt) {
}
