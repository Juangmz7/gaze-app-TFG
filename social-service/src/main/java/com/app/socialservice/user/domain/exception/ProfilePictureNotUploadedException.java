package com.app.socialservice.user.domain.exception;

import com.app.socialservice.shared.domain.exception.DomainException;

public class ProfilePictureNotUploadedException extends DomainException {

    public ProfilePictureNotUploadedException(String message) {
        super(message);
    }
}
