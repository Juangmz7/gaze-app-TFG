package com.app.socialservice.report.application.commands;

import java.util.UUID;

import com.app.socialservice.report.domain.enums.UserReportReason;

/**
 * details is the raw text: the domain trims it and checks its length and whether the reason
 * requires it.
 */
public record ReportUserCommand(
        UUID reporterUserId,
        UUID reportedUserId,
        UserReportReason reason,
        String details
) {
}
