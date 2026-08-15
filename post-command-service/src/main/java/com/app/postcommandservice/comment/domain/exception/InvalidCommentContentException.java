package com.app.postcommandservice.comment.domain.exception;

import com.app.postcommandservice.shared.domain.exception.DomainException;

public class InvalidCommentContentException extends DomainException {

    public InvalidCommentContentException(String message) {
        super(message);
    }
}
