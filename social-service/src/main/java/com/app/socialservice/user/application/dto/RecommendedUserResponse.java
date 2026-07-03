package com.app.socialservice.user.application.dto;

import java.util.UUID;

public record RecommendedUserResponse(
        UUID userId,
        String username,
        String description,
        String profilePic,
        boolean followsYou
) {
}
