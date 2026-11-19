package com.app.postcommandservice.report.application.dto;

/** {@code created} is false when an OPEN report of the same reporter and target was returned. */
public record ReportCreationResult(ReportReceiptResponse response, boolean created) {
}
