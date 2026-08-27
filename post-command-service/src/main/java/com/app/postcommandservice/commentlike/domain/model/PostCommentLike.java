package com.app.postcommandservice.commentlike.domain.model;

import java.time.Instant;
import java.util.Objects;

import com.app.postcommandservice.comment.domain.model.valueobj.CommentId;
import com.app.postcommandservice.shared.domain.model.user.valueobj.UserId;

public class PostCommentLike {

    private final CommentId commentId;
    private final UserId userId;
    private final CommentLikeContext context;
    private final Instant createdAt;

    public PostCommentLike(CommentId commentId, UserId userId, CommentLikeContext context, Instant createdAt) {
        this.commentId = Objects.requireNonNull(commentId, "commentId must not be null");
        this.userId = Objects.requireNonNull(userId, "userId must not be null");
        this.context = Objects.requireNonNull(context, "context must not be null");
        this.createdAt = createdAt;
    }

    public static PostCommentLike create(CommentId commentId, UserId userId, CommentLikeContext context) {
        return new PostCommentLike(commentId, userId, context, null);
    }

    public CommentId getCommentId() {
        return commentId;
    }

    public UserId getUserId() {
        return userId;
    }

    public CommentLikeContext getContext() {
        return context;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
