package com.app.postcommandservice.comment.domain.model;

import java.util.Objects;

public record CommentUpdateResult(
        Comment comment,
        boolean changed
) {

    public CommentUpdateResult {
        Objects.requireNonNull(comment, "comment must not be null");
    }
}
