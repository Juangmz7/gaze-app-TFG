package com.app.socialservice.user.application.dto;

import java.util.Map;

public record OwnUserProfileData(
        String username,
        String description,
        Map<String, String> socialMedia,
        long postCount,
        String profilePic,
        boolean banned
) {

    public OwnUserProfileData {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("username must not be null or blank");
        }
        if (postCount < 0) {
            throw new IllegalArgumentException("postCount must not be negative");
        }

        socialMedia = socialMedia == null ? null : Map.copyOf(socialMedia);
    }
}
