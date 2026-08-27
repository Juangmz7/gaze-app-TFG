package com.app.postcommandservice.comment.domain.exception;

import java.util.UUID;

import com.app.postcommandservice.comment.domain.model.valueobj.CommentStatus;
import com.app.postcommandservice.shared.domain.exception.DomainException;

public class CommentNotActiveException extends DomainException {

    public CommentNotActiveException(UUID commentId, CommentStatus status) {
        super(String.format("Comment %s must be ACTIVE, but was %s", commentId, status));
    }
}
