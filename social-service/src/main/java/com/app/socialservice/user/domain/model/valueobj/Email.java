package com.app.socialservice.user.domain.model.valueobj;

import com.app.socialservice.user.domain.exception.InvalidEmailException;

import java.util.regex.Pattern;

public record Email(String value) {

    // RFC 5322 simplified, covers the vast majority of real-world email addresses
    private static final Pattern PATTERN = Pattern.compile(
            "^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$"
    );

    public Email {
        if (value == null || value.isBlank()) {
            throw new InvalidEmailException("Email must not be null or blank");
        }
        if (!PATTERN.matcher(value).matches() && !value.contains("_deleted_")) {
            throw new InvalidEmailException("Invalid email format: " + value);
        }
    }
}
