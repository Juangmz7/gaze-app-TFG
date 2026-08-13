package com.app.postcommandservice.post.domain.exception;

import com.app.postcommandservice.shared.domain.exception.DomainException;

public class InvalidTaggedUsernameException extends DomainException {

    public InvalidTaggedUsernameException(String message) {
        super(message);
    }
}
