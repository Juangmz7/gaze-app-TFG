package com.app.socialservice.user.application.dto;

import java.util.UUID;

import com.app.socialservice.shared.application.media.SignablePictureUrl;

public record RecommendedUserResponse(
        UUID userId,
        String username,
        String description,
        String profilePic,
        boolean followsYou
) implements SignablePictureUrl<RecommendedUserResponse> {

    @Override
    public String pictureUrlToSign() {
        return profilePic;
    }

    @Override
    public RecommendedUserResponse withPictureUrl(String pictureUrl) {
        return new RecommendedUserResponse(
                userId,
                username,
                description,
                pictureUrl,
                followsYou
        );
    }
}
