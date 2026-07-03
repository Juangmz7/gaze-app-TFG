package com.app.socialservice.user.domain.exception;

import com.app.socialservice.shared.domain.exception.DomainException;

public class UserProfileBlockedException extends DomainException {

    public UserProfileBlockedException(String message) {
        super(message);
    }
}
