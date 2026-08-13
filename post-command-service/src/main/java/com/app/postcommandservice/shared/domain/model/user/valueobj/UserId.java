package com.app.postcommandservice.shared.domain.model.user.valueobj;

import com.app.postcommandservice.shared.domain.exception.InvalidUserIdException;

import java.util.UUID;

public record UserId(UUID value) {

    public UserId {
        if (value == null) {
            throw new InvalidUserIdException("User ID must not be null");
        }
    }
}
