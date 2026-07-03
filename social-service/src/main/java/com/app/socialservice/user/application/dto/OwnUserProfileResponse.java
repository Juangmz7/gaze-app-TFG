package com.app.socialservice.user.application.dto;

import java.util.Map;
import java.util.UUID;

public record OwnUserProfileResponse(
        UUID userId,
        String username,
        String description,
        Map<String, String> socialMedia,
        long followersCount,
        long followingCount,
        long postCount,
        String profilePicture,
        boolean isBanned
) {
}
