package com.app.postcommandservice.post.domain.model;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

public record PostUpdateResult(
        Post post,
        boolean changed,
        Set<String> newlyTaggedUsers
) {

    public PostUpdateResult {
        Objects.requireNonNull(post, "post must not be null");
        Objects.requireNonNull(newlyTaggedUsers, "newlyTaggedUsers must not be null");
        newlyTaggedUsers = Collections.unmodifiableSet(new LinkedHashSet<>(newlyTaggedUsers));
    }
}
