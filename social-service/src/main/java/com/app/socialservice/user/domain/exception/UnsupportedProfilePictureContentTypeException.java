package com.app.socialservice.user.domain.exception;

import com.app.socialservice.shared.domain.exception.DomainException;

public class UnsupportedProfilePictureContentTypeException extends DomainException {

    public UnsupportedProfilePictureContentTypeException(String message) {
        super(message);
    }
}
