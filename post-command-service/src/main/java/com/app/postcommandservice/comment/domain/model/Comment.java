package com.app.postcommandservice.comment.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.app.postcommandservice.comment.domain.exception.CommentNotActiveException;
import com.app.postcommandservice.comment.domain.model.valueobj.CommentContent;
import com.app.postcommandservice.comment.domain.model.valueobj.CommentId;
import com.app.postcommandservice.comment.domain.model.valueobj.CommentStatus;
import com.app.postcommandservice.post.domain.model.valueobj.PostId;
import com.app.postcommandservice.shared.domain.model.user.valueobj.UserId;

public class Comment {

    private final CommentId id;
    private final PostId postId;
    private final UserId userId;
    private final CommentContent content;
    private final UUID replyTo;
    private final CommentStatus status;
    private final Instant createdAt;
    private final Instant updatedAt;
    private final Instant deletedAt;

    public Comment(
            CommentId id,
            PostId postId,
            UserId userId,
            CommentContent content,
            UUID replyTo,
            CommentStatus status,
            Instant createdAt,
            Instant updatedAt,
            Instant deletedAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.postId = Objects.requireNonNull(postId, "postId must not be null");
        this.userId = Objects.requireNonNull(userId, "userId must not be null");
        this.content = Objects.requireNonNull(content, "content must not be null");
        this.replyTo = replyTo;
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.deletedAt = deletedAt;
    }

    public static Comment create(CommentId id, PostId postId, UserId userId, CommentContent content, UUID replyTo) {
        return new Comment(id, postId, userId, content, replyTo, CommentStatus.ACTIVE, null, null, null);
    }

    public Comment delete(Instant deletedAt) {
        Objects.requireNonNull(deletedAt, "deletedAt must not be null");

        if (status != CommentStatus.ACTIVE) {
            throw new CommentNotActiveException(id.value(), status);
        }

        return new Comment(id, postId, userId, content, replyTo, CommentStatus.DELETED, createdAt, updatedAt, deletedAt);
    public CommentUpdateResult update(String newContent) {
        var updatedContent = new CommentContent(newContent);
        if (status != CommentStatus.ACTIVE) {
            throw new CommentNotActiveException(id.value(), status);
        }
        if (content.equals(updatedContent)) {
            return new CommentUpdateResult(this, false);
        }

        return new CommentUpdateResult(
                new Comment(id, postId, userId, updatedContent, replyTo, status, createdAt, updatedAt, deletedAt),
                true
        );
    }

    public CommentId getId() {
        return id;
    }

    public PostId getPostId() {
        return postId;
    }

    public UserId getUserId() {
        return userId;
    }

    public CommentContent getContent() {
        return content;
    }

    public UUID getReplyTo() {
        return replyTo;
    }

    public CommentStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }
}
