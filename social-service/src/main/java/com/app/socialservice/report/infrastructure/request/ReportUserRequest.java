package com.app.socialservice.report.infrastructure.request;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

import com.app.socialservice.report.domain.enums.UserReportReason;

/**
 * A malformed reportedUserId or an unknown reason fails JSON parsing (400). details is trimmed
 * and checked by the domain: at most 1000 characters, required when the reason is OTHER.
 */
public record ReportUserRequest(
        @NotNull(message = "reportedUserId is required")
        UUID reportedUserId,
        @NotNull(message = "reason is required")
        UserReportReason reason,
        String details
) {
}
