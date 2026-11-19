package com.app.socialservice.user.infrastructure.request;

import java.math.BigDecimal;

/**
 * Both fields are checked by the use case against the domain rules, so that a missing or
 * wrong value answers the dedicated error codes (unsupported_content_type, invalid_size)
 * rather than a generic validation error. sizeBytes is a BigDecimal so a fractional number is
 * rejected instead of silently truncated.
 */
public record ProfilePictureUploadUrlRequest(
        String contentType,
        BigDecimal sizeBytes
) {
}
