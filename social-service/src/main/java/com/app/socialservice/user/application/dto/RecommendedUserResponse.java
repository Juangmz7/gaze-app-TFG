package com.app.socialservice.user.application.dto;

public record RecommendedUserResponse(
        String username,
        String description,
        String profilePic,
        boolean followsYou
) {
}
