package com.app.socialservice.user.application.dto;

import java.util.Map;
import java.util.UUID;

public record OwnUserProfileResponse(
        UUID userId,
        String description,
        String profilePicture,
        Map<String, String> socialMedia
) {
}
