package com.app.socialservice.user.domain.model.valueobj;

import java.util.Map;

public record UserBio(
        String description,
        Map<String, String> socialMedia
) {

    public static final int MAX_DESCRIPTION_LENGTH = 255;

    public UserBio {
        if (description != null && description.length() > MAX_DESCRIPTION_LENGTH) {
            throw new IllegalArgumentException(
                    "User bio description must not exceed %d characters".formatted(MAX_DESCRIPTION_LENGTH)
            );
        }
        socialMedia = socialMedia == null ? Map.of() : Map.copyOf(socialMedia);
    }
}
