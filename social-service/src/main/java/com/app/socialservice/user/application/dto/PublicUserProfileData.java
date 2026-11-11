package com.app.socialservice.user.application.dto;

import java.util.Map;
import java.util.UUID;

/** A user's profile fields that are the same whoever asks. */
public record PublicUserProfileData(
        UUID userId,
        String username,
        String description,
        Map<String, String> socialMedia,
        String profilePic,
        boolean banned
) {

    public PublicUserProfileData {
        socialMedia = socialMedia == null ? null : Map.copyOf(socialMedia);
    }
}
