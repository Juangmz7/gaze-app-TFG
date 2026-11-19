package com.app.socialservice.user.domain.enums;

import java.util.Arrays;

import com.app.socialservice.user.domain.exception.UnsupportedProfilePictureContentTypeException;

/**
 * The image types an avatar may be uploaded as, with the blob extension each one gets.
 */
public enum ProfilePictureContentType {
    JPEG("image/jpeg", "jpg"),
    PNG("image/png", "png"),
    WEBP("image/webp", "webp");

    private final String mimeType;
    private final String extension;

    ProfilePictureContentType(String mimeType, String extension) {
        this.mimeType = mimeType;
        this.extension = extension;
    }

    public String mimeType() {
        return mimeType;
    }

    public String extension() {
        return extension;
    }

    public static ProfilePictureContentType fromMimeType(String mimeType) {
        return Arrays.stream(values())
                .filter(type -> type.mimeType.equals(mimeType))
                .findFirst()
                .orElseThrow(() -> new UnsupportedProfilePictureContentTypeException(
                        "contentType must be one of image/jpeg, image/png or image/webp"));
    }

    public static boolean isKnownExtension(String extension) {
        return Arrays.stream(values()).anyMatch(type -> type.extension.equals(extension));
    }
}
