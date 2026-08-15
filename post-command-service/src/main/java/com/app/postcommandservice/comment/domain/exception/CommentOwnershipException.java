package com.app.postcommandservice.comment.domain.exception;

import java.util.UUID;

import com.app.postcommandservice.shared.domain.exception.DomainException;

public class CommentOwnershipException extends DomainException {

    public CommentOwnershipException(UUID commentId, UUID userId) {
        super(String.format("User %s cannot modify comment %s", userId, commentId));
    }
}
