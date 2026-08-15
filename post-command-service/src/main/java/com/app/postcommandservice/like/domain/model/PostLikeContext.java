package com.app.postcommandservice.like.domain.model;

import java.util.Objects;

public record PostLikeContext(PostLikeSource source, int feedPosition) {

    public PostLikeContext {
        Objects.requireNonNull(source, "source must not be null");
        if (feedPosition < 0) {
            throw new IllegalArgumentException("feedPosition must be zero or greater");
        }
    }
}
