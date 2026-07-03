package com.app.socialservice.user.domain.exception;

import com.app.socialservice.shared.domain.exception.DomainException;

public class InvalidUserIdException extends DomainException {

    public InvalidUserIdException(String message) {
        super(message);
    }
}
