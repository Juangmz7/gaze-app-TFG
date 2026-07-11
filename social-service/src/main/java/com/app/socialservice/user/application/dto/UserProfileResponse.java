package com.app.socialservice.user.application.dto;

import java.util.Map;
import java.util.UUID;

public record UserProfileResponse(
        UUID id,
        String username,
        String description,
        Map<String, String> socialMedia,
        long followerCount,
        long followingCount,
        long postCount,
        String profilePic,
        boolean following,
        boolean followsMe
) {

    public UserProfileResponse {
        socialMedia = socialMedia == null ? null : Map.copyOf(socialMedia);
    }
}
