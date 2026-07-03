package com.app.socialservice.user.domain.model.valueobj;

import com.app.socialservice.user.domain.exception.InvalidProfilePictureUrlException;

import java.net.URI;

public record ProfilePictureUrl(String value) {

    public static final int MAX_LENGTH = 255;

    public ProfilePictureUrl {
        if (value == null || value.isBlank()) {
            throw new InvalidProfilePictureUrlException("Profile picture URL must not be null or blank");
        }
        if (value.length() > MAX_LENGTH) {
            throw new InvalidProfilePictureUrlException(
                    "Profile picture URL must not exceed %d characters".formatted(MAX_LENGTH)
            );
        }
        try {
            URI uri = URI.create(value);
            if (uri.getScheme() == null || !uri.getScheme().matches("https?")) {
                throw new InvalidProfilePictureUrlException(
                        "Profile picture URL must use HTTP or HTTPS scheme: " + value
                );
            }
        } catch (IllegalArgumentException e) {
            throw new InvalidProfilePictureUrlException("Invalid profile picture URL format: " + value);
        }
    }
}
