package com.app.socialservice.shared.infrastructure.security;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum ApiErrorCode {
    NOT_FOUND,
    BAD_REQUEST,
    BLOCKED,
    USER_BANNED,
    INVALID_JWT,
    CONFLICT,
    VALIDATION_ERROR,
    INTERNAL_ERROR,
    // Feature-specific codes are snake_case on the wire, as the API contract spells them.
    @JsonProperty("unsupported_content_type")
    UNSUPPORTED_CONTENT_TYPE,
    @JsonProperty("invalid_size")
    INVALID_SIZE,
    @JsonProperty("invalid_profile_picture")
    INVALID_PROFILE_PICTURE,
    @JsonProperty("profile_picture_not_uploaded")
    PROFILE_PICTURE_NOT_UPLOADED,
    @JsonProperty("invalid_username")
    INVALID_USERNAME
}
