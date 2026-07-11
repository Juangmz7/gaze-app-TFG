package com.app.socialservice.block.application.dto;

import java.util.UUID;

public record BlockedUserDetails(
        UUID userId,
        String username,
        String profilePic
) {
}
