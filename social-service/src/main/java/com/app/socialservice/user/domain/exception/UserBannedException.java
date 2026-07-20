package com.app.socialservice.user.domain.exception;

import java.util.UUID;

import com.app.socialservice.shared.domain.exception.DomainException;

public class UserBannedException extends DomainException {

    public UserBannedException(UUID userId) {
        super("User is banned: " + userId);
    }
}
