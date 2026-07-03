package com.app.socialservice.user.domain.exception;

import com.app.socialservice.shared.domain.exception.DomainException;

public class InvalidProfilePictureUrlException extends DomainException {

    public InvalidProfilePictureUrlException(String message) {
        super(message);
    }
}
