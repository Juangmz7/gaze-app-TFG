package com.app.socialservice.user.application.dto;

import java.time.Instant;
import java.util.UUID;

public record RecommendedUserDetails(
        UUID id,
        String username,
        String description,
        String profilePic,
        boolean followsYou,
        Instant createdAt
) {
}
