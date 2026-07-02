package com.app.socialservice.user.application.dto;

import java.util.Map;

public record UserProfileResponse(
        String username,
        String description,
        Map<String, String> socialMedia,
        long followerCount,
        long followingCount,
        long postCount,
        String profilePic,
        boolean following,
        boolean isBanned
) {

    public UserProfileResponse {
        socialMedia = socialMedia == null ? null : Map.copyOf(socialMedia);
    }
}
