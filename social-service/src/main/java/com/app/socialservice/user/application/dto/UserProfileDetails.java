package com.app.socialservice.user.application.dto;

import java.util.Map;
import java.util.UUID;

public record UserProfileDetails(
        UUID userId,
        String username,
        String description,
        Map<String, String> socialMedia,
        String profilePic,
        boolean following,
        boolean followsMe,
        boolean blocked,
        boolean banned
) {

    public UserProfileDetails {
        socialMedia = socialMedia == null ? null : Map.copyOf(socialMedia);
    }
}
