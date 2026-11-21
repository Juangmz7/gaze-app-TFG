package com.app.postcommandservice.report.infrastructure.controller;

import jakarta.validation.constraints.NotNull;

import com.app.postcommandservice.report.domain.model.valueobj.ReportReason;

/** {@code details} is trimmed and length-checked by the domain (required for OTHER). */
public record CreateReportRequest(
        @NotNull(message = "reason is required")
        ReportReason reason,
        String details) {
}
