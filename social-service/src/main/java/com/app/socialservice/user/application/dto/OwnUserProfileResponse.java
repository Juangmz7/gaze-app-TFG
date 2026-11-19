package com.app.socialservice.user.application.dto;

import java.util.Map;
import java.util.UUID;

import com.app.socialservice.shared.application.media.SignablePictureUrl;

public record OwnUserProfileResponse(
        UUID userId,
        String username,
        String description,
        Map<String, String> socialMedia,
        long followersCount,
        long followingCount,
        long postCount,
        String profilePicture
) implements SignablePictureUrl<OwnUserProfileResponse> {

    @Override
    public String pictureUrlToSign() {
        return profilePicture;
    }

    @Override
    public OwnUserProfileResponse withPictureUrl(String pictureUrl) {
        return new OwnUserProfileResponse(
                userId,
                username,
                description,
                socialMedia,
                followersCount,
                followingCount,
                postCount,
                pictureUrl
        );
    }
}
