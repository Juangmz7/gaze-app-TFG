package com.app.socialservice.user.application.commands;

import java.util.Map;
import java.util.UUID;

public record UpdateOwnUserProfileCommand(
        UUID userId,
        String description,
        String profilePicture,
        Map<String, String> socialMedia
) {
}
