package com.app.socialservice.user.domain.model.valueobj;

import java.util.Map;

public record UserBio(
        String description,
        Map<String, String> socialMedia
) {

    public UserBio {
        socialMedia = socialMedia == null ? Map.of() : Map.copyOf(socialMedia);
    }
}
