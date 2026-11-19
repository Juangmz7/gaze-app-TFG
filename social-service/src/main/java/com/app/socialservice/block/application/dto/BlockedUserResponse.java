package com.app.socialservice.block.application.dto;

import java.util.UUID;

import com.app.socialservice.shared.application.media.SignablePictureUrl;

public record BlockedUserResponse(
        UUID userId,
        String username,
        String profilePic
) implements SignablePictureUrl<BlockedUserResponse> {

    @Override
    public String pictureUrlToSign() {
        return profilePic;
    }

    @Override
    public BlockedUserResponse withPictureUrl(String pictureUrl) {
        return new BlockedUserResponse(
                userId,
                username,
                pictureUrl
        );
    }
}
