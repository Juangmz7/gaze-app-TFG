package com.app.postcommandservice.post.domain.exception;

import com.app.postcommandservice.shared.domain.exception.DomainException;

public class InvalidPostTagException extends DomainException {

    public InvalidPostTagException(String message) {
        super(message);
    }
}
