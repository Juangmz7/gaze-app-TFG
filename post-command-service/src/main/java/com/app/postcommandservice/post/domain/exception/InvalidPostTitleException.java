package com.app.postcommandservice.post.domain.exception;

import com.app.postcommandservice.shared.domain.exception.DomainException;

public class InvalidPostTitleException extends DomainException {
    public InvalidPostTitleException(String message) {
        super(message);
    }
}
