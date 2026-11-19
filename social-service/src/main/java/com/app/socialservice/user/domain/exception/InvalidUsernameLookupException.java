package com.app.socialservice.user.domain.exception;

import com.app.socialservice.shared.domain.exception.DomainException;

public class InvalidUsernameLookupException extends DomainException {

    public InvalidUsernameLookupException(String message) {
        super(message);
    }
}
