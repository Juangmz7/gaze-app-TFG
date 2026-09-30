package com.app.postcommandservice.post.domain.exception;

import com.app.postcommandservice.shared.domain.exception.DomainException;

public class InvalidPostMediaException extends DomainException {
    public InvalidPostMediaException(String message) {
        super(message);
    }
}
