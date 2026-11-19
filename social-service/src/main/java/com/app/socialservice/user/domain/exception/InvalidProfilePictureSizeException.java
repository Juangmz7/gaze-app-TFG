package com.app.socialservice.user.domain.exception;

import com.app.socialservice.shared.domain.exception.DomainException;

public class InvalidProfilePictureSizeException extends DomainException {

    public InvalidProfilePictureSizeException(String message) {
        super(message);
    }
}
