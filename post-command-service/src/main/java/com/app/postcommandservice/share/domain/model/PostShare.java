package com.app.postcommandservice.share.domain.model;

import java.time.Instant;
import java.util.Objects;

import com.app.postcommandservice.post.domain.model.valueobj.PostId;
import com.app.postcommandservice.shared.domain.model.user.valueobj.UserId;

public class PostShare {

    private final PostId postId;
    private final UserId userId;
    private final Instant createdAt;

    public PostShare(PostId postId, UserId userId, Instant createdAt) {
        this.postId = Objects.requireNonNull(postId, "postId must not be null");
        this.userId = Objects.requireNonNull(userId, "userId must not be null");
        this.createdAt = createdAt;
    }

    public static PostShare create(PostId postId, UserId userId) {
        return new PostShare(postId, userId, null);
    }

    public PostId getPostId() {
        return postId;
    }

    public UserId getUserId() {
        return userId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
