package com.app.postcommandservice.comment.domain.model.valueobj;

import java.util.UUID;

import com.app.postcommandservice.comment.domain.exception.InvalidCommentIdException;

public record CommentId(UUID value) {

    public CommentId {
        if (value == null) {
            throw new InvalidCommentIdException("Comment ID must not be null");
        }
    }
}
