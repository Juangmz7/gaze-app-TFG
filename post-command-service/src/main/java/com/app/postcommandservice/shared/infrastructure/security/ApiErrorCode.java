package com.app.postcommandservice.shared.infrastructure.security;

public enum ApiErrorCode {
    NOT_FOUND,
    FORBIDDEN,
    BAD_REQUEST,
    BLOCKED,
    USER_BANNED,
    INVALID_JWT,
    CONFLICT,
    VALIDATION_ERROR,
    INTERNAL_ERROR
}
