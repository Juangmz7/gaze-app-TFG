package com.app.postcommandservice.comment.domain.exception;

import java.util.UUID;

import com.app.postcommandservice.shared.domain.exception.DomainException;

public class CommentNotFoundException extends DomainException {

    public CommentNotFoundException(UUID commentId) {
        super(String.format("Comment not found: %s", commentId));
    }
}
