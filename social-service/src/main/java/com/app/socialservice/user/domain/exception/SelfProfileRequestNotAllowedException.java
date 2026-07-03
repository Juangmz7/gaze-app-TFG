package com.app.socialservice.user.domain.exception;

import com.app.socialservice.shared.domain.exception.DomainException;

public class SelfProfileRequestNotAllowedException extends DomainException {

    public SelfProfileRequestNotAllowedException(String message) {
        super(message);
    }
}
