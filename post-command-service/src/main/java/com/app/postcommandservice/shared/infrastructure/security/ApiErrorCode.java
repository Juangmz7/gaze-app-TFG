package com.app.postcommandservice.shared.infrastructure.security;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum ApiErrorCode {
    NOT_FOUND,
    FORBIDDEN,
    BAD_REQUEST,
    BLOCKED,
    USER_BANNED,
    INVALID_JWT,
    CONFLICT,
    VALIDATION_ERROR,
    INTERNAL_ERROR,
    // Feature-specific codes are snake_case on the wire, as the API contract spells them.
    @JsonProperty("post_not_found")
    POST_NOT_FOUND,
    @JsonProperty("comment_not_found")
    COMMENT_NOT_FOUND,
    @JsonProperty("cannot_report_own_content")
    CANNOT_REPORT_OWN_CONTENT,
    @JsonProperty("invalid_cursor")
    INVALID_CURSOR,
    @JsonProperty("invalid_limit")
    INVALID_LIMIT
}
