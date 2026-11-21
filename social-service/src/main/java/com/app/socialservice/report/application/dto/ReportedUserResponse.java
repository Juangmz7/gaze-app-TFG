package com.app.socialservice.report.application.dto;

import java.util.UUID;

public record ReportedUserResponse(
        UUID userId,
        String username,
        String profilePic
) {
}
