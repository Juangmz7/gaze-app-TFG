package com.app.socialservice.user.domain.model.valueobj;

import java.math.BigDecimal;

import com.app.socialservice.user.domain.exception.InvalidProfilePictureSizeException;

/**
 * Declared size of an avatar upload: a whole number of bytes from 1 to 5 MiB.
 */
public record ProfilePictureSize(long bytes) {

    public static final long MIN_BYTES = 1;
    public static final long MAX_BYTES = 5L * 1024 * 1024;

    public ProfilePictureSize {
        if (bytes < MIN_BYTES || bytes > MAX_BYTES) {
            throw invalidSize();
        }
    }

    public static ProfilePictureSize of(BigDecimal sizeBytes) {
        if (sizeBytes == null) {
            throw invalidSize();
        }
        try {
            return new ProfilePictureSize(sizeBytes.longValueExact());
        } catch (ArithmeticException exception) {
            throw invalidSize();
        }
    }

    private static InvalidProfilePictureSizeException invalidSize() {
        return new InvalidProfilePictureSizeException(
                String.format("sizeBytes must be a whole number from %d to %d", MIN_BYTES, MAX_BYTES));
    }
}
