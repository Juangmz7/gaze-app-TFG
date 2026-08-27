package com.app.postcommandservice.commentlike.domain.model;

public record CommentLikeContext(CommentLikeSource source, int feedPosition) {

    public CommentLikeContext {
        if (source == null) {
            throw new IllegalArgumentException("source must not be null");
        }
        if (feedPosition < 0) {
            throw new IllegalArgumentException("feedPosition must be zero or greater");
        }
    }
}
