package com.app.socialservice.user.infrastructure.events;

import lombok.Builder;

import java.util.Map;

@Builder
public record UserBioEventPayload(
        String description,
        Map<String, String> socialMedia
) {

    public UserBioEventPayload {
        socialMedia = socialMedia == null ? Map.of() : Map.copyOf(socialMedia);
    }
}
