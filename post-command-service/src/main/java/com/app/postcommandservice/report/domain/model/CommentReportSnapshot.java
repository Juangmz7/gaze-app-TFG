package com.app.postcommandservice.report.domain.model;

import java.util.Objects;
import java.util.UUID;

/** Evidence captured when a comment is reported: the comment can be edited or deleted later. */
public record CommentReportSnapshot(String content, UUID authorUserId, UUID postId) {

    public CommentReportSnapshot {
        Objects.requireNonNull(content, "content must not be null");
        Objects.requireNonNull(authorUserId, "authorUserId must not be null");
        Objects.requireNonNull(postId, "postId must not be null");
    }
}
