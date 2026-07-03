package com.app.socialservice.user.domain.exception;

public class SelfProfileRequestNotAllowedException extends DomainException {

    public SelfProfileRequestNotAllowedException(String message) {
        super(message);
    }
}
