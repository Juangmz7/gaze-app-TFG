package com.app.socialservice.follow.application.dto;

import java.util.UUID;

import com.app.socialservice.shared.application.media.SignablePictureUrl;

/**
 * following: whether the caller follows this user.
 */
public record FollowListItemResponse(
        UUID userId,
        String username,
        String profilePic,
        boolean following
) implements SignablePictureUrl<FollowListItemResponse> {

    @Override
    public String pictureUrlToSign() {
        return profilePic;
    }

    @Override
    public FollowListItemResponse withPictureUrl(String pictureUrl) {
        return new FollowListItemResponse(userId, username, pictureUrl, following);
    }
}
