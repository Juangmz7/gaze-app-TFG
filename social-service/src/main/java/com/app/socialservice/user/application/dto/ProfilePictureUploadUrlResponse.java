package com.app.socialservice.user.application.dto;

import java.time.Instant;

public record ProfilePictureUploadUrlResponse(
        String uploadUrl,
        String pictureUrl,
        Instant expiresAt
) {
}
