package com.app.socialservice.user.domain.exception;

import com.app.socialservice.shared.domain.exception.DomainException;

public class InvalidEmailException extends DomainException {

    public InvalidEmailException(String message) {
        super(message);
    }
}
