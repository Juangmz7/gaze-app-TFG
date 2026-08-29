package com.app.postcommandservice.collab.domain.exception;

import com.app.postcommandservice.shared.domain.exception.DomainException;

public class InvalidCollabTitleException extends DomainException {

    public InvalidCollabTitleException(String message) {
        super(message);
    }
}
