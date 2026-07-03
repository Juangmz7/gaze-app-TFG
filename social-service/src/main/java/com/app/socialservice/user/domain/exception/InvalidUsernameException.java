package com.app.socialservice.user.domain.exception;

import com.app.socialservice.shared.domain.exception.DomainException;

public class InvalidUsernameException extends DomainException {

    public InvalidUsernameException(String message) {
        super(message);
    }
}
