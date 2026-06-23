package com.app.socialservice.follow.domain.exception;

public class SelfFollowNotAllowedException extends DomainException {

    public SelfFollowNotAllowedException(String message) {
        super(message);
    }
}
