package com.app.postcommandservice.comment.domain.exception;

import com.app.postcommandservice.shared.domain.exception.DomainException;

public class CommentBlockedException extends DomainException {

    public CommentBlockedException(String target) {
        super(String.format("Comment creation is forbidden due to a blocked relationship with %s", target));
    }
}
