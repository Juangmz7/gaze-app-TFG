package com.app.socialservice.user.domain.model.valueobj;

import com.app.socialservice.user.domain.exception.InvalidUsernameException;

public record Username(String value) {

    private static final int MIN_LENGTH = 3;
    private static final int MAX_LENGTH = 100;

    public Username {
        if (value == null || value.isBlank()) {
            throw new InvalidUsernameException("Username must not be null or blank");
        }
        if (value.length() < MIN_LENGTH || value.length() > MAX_LENGTH) {
            throw new InvalidUsernameException(
                    "Username must be between " + MIN_LENGTH + " and " + MAX_LENGTH
                            + " characters, but was: " + value.length()
            );
        }
    }
}
