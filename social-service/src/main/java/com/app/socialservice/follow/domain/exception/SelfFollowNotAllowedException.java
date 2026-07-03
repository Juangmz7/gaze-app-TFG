package com.app.socialservice.follow.domain.exception;

import com.app.socialservice.shared.domain.exception.DomainException;

public class SelfFollowNotAllowedException extends DomainException {

    public SelfFollowNotAllowedException(String message) {
        super(message);
    }
}
