package com.app.socialservice.user.application.dto;

import java.util.Map;
import java.util.UUID;

/**
 * The cached part of a public profile (CacheNames.PUBLIC_PROFILE): the same whoever
 * asks, so it is cached once per user. UserProfileResponse adds what depends on the
 * requester.
 */
public record PublicUserProfile(
        UUID userId,
        String username,
        String description,
        Map<String, String> socialMedia,
        String profilePic,
        long followersCount,
        long followingCount,
        long postCount
) {

    public PublicUserProfile {
        socialMedia = socialMedia == null ? null : Map.copyOf(socialMedia);
    }
}
