package com.app.socialservice.follow.domain.exception;

public class SelfUnfollowNotAllowedException extends DomainException {

    public SelfUnfollowNotAllowedException(String message) {
        super(message);
    }
}
