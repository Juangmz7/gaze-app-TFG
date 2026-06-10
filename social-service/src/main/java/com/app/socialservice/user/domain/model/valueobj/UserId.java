package com.app.socialservice.user.domain.model.valueobj;

import com.app.socialservice.user.domain.exception.InvalidUserIdException;

import java.util.UUID;

public record UserId(UUID value) {

    public UserId {
        if (value == null) {
            throw new InvalidUserIdException("User ID must not be null");
        }
    }
}
