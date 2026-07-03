package com.app.socialservice.follow.domain.exception;

import com.app.socialservice.shared.domain.exception.DomainException;

public class SelfUnfollowNotAllowedException extends DomainException {

    public SelfUnfollowNotAllowedException(String message) {
        super(message);
    }
}
