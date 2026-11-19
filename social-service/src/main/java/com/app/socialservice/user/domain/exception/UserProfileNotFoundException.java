package com.app.socialservice.user.domain.exception;

import java.util.UUID;

import com.app.socialservice.shared.domain.exception.DomainException;

public class UserProfileNotFoundException extends DomainException {

    public UserProfileNotFoundException(UUID userId) {
        super("User profile not found: " + userId);
    }

    private UserProfileNotFoundException(String message) {
        super(message);
    }

    public static UserProfileNotFoundException forUsername(String username) {
        return new UserProfileNotFoundException("User profile not found: " + username);
    }
}
