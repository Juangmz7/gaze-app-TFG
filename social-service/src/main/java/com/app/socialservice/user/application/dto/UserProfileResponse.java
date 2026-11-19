package com.app.socialservice.user.application.dto;

import java.util.Map;
import java.util.UUID;

import com.app.socialservice.shared.application.media.SignablePictureUrl;

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
) implements SignablePictureUrl<UserProfileResponse> {

    public UserProfileResponse {
        socialMedia = socialMedia == null ? null : Map.copyOf(socialMedia);
    }

    @Override
    public String pictureUrlToSign() {
        return profilePic;
    }

    @Override
    public UserProfileResponse withPictureUrl(String pictureUrl) {
        return new UserProfileResponse(
                id,
                username,
                description,
                socialMedia,
                followerCount,
                followingCount,
                postCount,
                pictureUrl,
                following,
                followsMe
        );
    }
}
