package com.app.socialservice.follow.domain.exception;

import com.app.socialservice.shared.domain.exception.DomainException;

public class FollowBlockedException extends DomainException {

    public FollowBlockedException(String message) {
        super(message);
    }
}
