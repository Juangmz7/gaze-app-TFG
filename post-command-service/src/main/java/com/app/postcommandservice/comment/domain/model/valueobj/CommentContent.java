package com.app.postcommandservice.comment.domain.model.valueobj;

import com.app.postcommandservice.comment.domain.exception.InvalidCommentContentException;

public record CommentContent(String value) {

    private static final int MAX_LENGTH = 4000;

    public CommentContent {
        if (value == null) {
            throw new InvalidCommentContentException("Comment content must not be null");
        }
        if (value.isBlank()) {
            throw new InvalidCommentContentException("Comment content must not be blank");
        }
        if (value.length() > MAX_LENGTH) {
            throw new InvalidCommentContentException("Comment content must be lower than or equal to 4000");
        }
    }
}
