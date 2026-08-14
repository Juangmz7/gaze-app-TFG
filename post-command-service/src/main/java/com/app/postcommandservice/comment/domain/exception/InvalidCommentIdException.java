package com.app.postcommandservice.comment.domain.exception;

import com.app.postcommandservice.shared.domain.exception.DomainException;

public class InvalidCommentIdException extends DomainException {

    public InvalidCommentIdException(String message) {
        super(message);
    }
}
