package com.app.socialservice.user.application.dto;

import java.util.Map;

public record OwnUserProfileResponse(
        String username,
        String description,
        Map<String, String> socialMedia,
        long followersCount,
        long followingCount,
        long postCount,
        String profilePic,
        boolean isBanned
) {

    public OwnUserProfileResponse {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("username must not be null or blank");
        }
        if (followersCount < 0) {
            throw new IllegalArgumentException("followersCount must not be negative");
        }
        if (followingCount < 0) {
            throw new IllegalArgumentException("followingCount must not be negative");
        }
        if (postCount < 0) {
            throw new IllegalArgumentException("postCount must not be negative");
        }

        socialMedia = socialMedia == null ? null : Map.copyOf(socialMedia);
    }
}
