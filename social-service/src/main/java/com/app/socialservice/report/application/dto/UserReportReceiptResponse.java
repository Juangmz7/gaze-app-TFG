package com.app.socialservice.report.application.dto;

import java.time.Instant;
import java.util.UUID;

import com.app.socialservice.report.domain.enums.ReportStatus;

public record UserReportReceiptResponse(
        UUID reportId,
        ReportStatus status,
        Instant createdAt
) {
}
