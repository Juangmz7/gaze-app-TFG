package com.app.socialservice.shared.domain.exception;

import java.util.UUID;

public class UserNotFoundException extends DomainException {

    public UserNotFoundException(UUID userId) {
        super("User not found: " + userId);
    }

    public UserNotFoundException(String message) {
        super(message);
    }
}
