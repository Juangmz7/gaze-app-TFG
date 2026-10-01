package com.app.postcommandservice.post.domain.model;

import java.util.Objects;

public record PostUpdateResult(
        Post post,
        boolean changed
) {

    public PostUpdateResult {
        Objects.requireNonNull(post, "post must not be null");
    }
}
