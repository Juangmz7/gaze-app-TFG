package com.app.socialservice.user.domain.exception;

import com.app.socialservice.shared.domain.exception.DomainException;

public class InvalidProfilePictureException extends DomainException {

    public InvalidProfilePictureException(String message) {
        super(message);
    }
}
