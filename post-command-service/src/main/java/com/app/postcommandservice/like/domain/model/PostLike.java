package com.app.postcommandservice.like.domain.model;

import java.time.Instant;
import java.util.Objects;

import com.app.postcommandservice.post.domain.model.valueobj.PostId;
import com.app.postcommandservice.shared.domain.model.user.valueobj.UserId;

public class PostLike {

    private final PostId postId;
    private final UserId userId;
    private final PostLikeContext context;
    private final Instant createdAt;

    public PostLike(PostId postId, UserId userId, PostLikeContext context, Instant createdAt) {
        this.postId = Objects.requireNonNull(postId, "postId must not be null");
        this.userId = Objects.requireNonNull(userId, "userId must not be null");
        this.context = Objects.requireNonNull(context, "context must not be null");
        this.createdAt = createdAt;
    }

    public static PostLike create(PostId postId, UserId userId, PostLikeContext context) {
        return new PostLike(postId, userId, context, null);
    }

    public PostId getPostId() {
        return postId;
    }

    public UserId getUserId() {
        return userId;
    }

    public PostLikeContext getContext() {
        return context;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
