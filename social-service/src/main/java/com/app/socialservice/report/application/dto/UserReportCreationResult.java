package com.app.socialservice.report.application.dto;

/**
 * created is false when the reporter's OPEN report on the same user was returned instead.
 */
public record UserReportCreationResult(UserReportReceiptResponse receipt, boolean created) {
}
