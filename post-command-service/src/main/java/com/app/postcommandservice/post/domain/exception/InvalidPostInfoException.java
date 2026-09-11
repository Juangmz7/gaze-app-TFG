package com.app.postcommandservice.post.domain.exception;

import com.app.postcommandservice.shared.domain.exception.DomainException;

public class InvalidPostInfoException extends DomainException {
    public InvalidPostInfoException(String message) {
        super(message);
    }
}
