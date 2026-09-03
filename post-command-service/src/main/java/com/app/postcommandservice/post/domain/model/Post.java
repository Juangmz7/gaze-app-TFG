package com.app.postcommandservice.post.domain.model;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.app.postcommandservice.post.domain.exception.PostNotActiveException;
import com.app.postcommandservice.post.domain.model.valueobj.PostDescription;
import com.app.postcommandservice.post.domain.model.valueobj.PostId;
import com.app.postcommandservice.post.domain.model.valueobj.PostStatus;
import com.app.postcommandservice.post.domain.model.valueobj.PostTaggedUsers;
import com.app.postcommandservice.post.domain.model.valueobj.PostTags;
import com.app.postcommandservice.post.domain.model.valueobj.PostType;
import com.app.postcommandservice.shared.domain.model.user.valueobj.UserId;

public class Post {

    private final PostId id;
    private final UserId userId;
    private final UUID collabId;
    private final PostType postType;
    private final PostDescription description;
    private final PostTaggedUsers taggedUsers;
    private final PostTags tags;
    private final PostStatus status;
    private final Instant createdAt;
    private final Instant updatedAt;

    public Post(
            PostId id,
            UserId userId,
            UUID collabId,
            PostType postType,
            PostDescription description,
            PostTaggedUsers taggedUsers,
            PostTags tags,
            PostStatus status,
            Instant createdAt,
            Instant updatedAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.userId = Objects.requireNonNull(userId, "userId must not be null");
        this.postType = Objects.requireNonNull(postType, "postType must not be null");
        this.description = Objects.requireNonNull(description, "description must not be null");
        this.taggedUsers = Objects.requireNonNull(taggedUsers, "taggedUsers must not be null");
        this.tags = Objects.requireNonNull(tags, "tags must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
        validateCollabLink(postType, collabId);
        this.collabId = collabId;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static Post create(
            PostId id,
            UserId userId,
            UUID collabId,
            PostType postType,
            PostDescription description,
            PostTaggedUsers taggedUsers,
            PostTags tags) {
        return new Post(id, userId, collabId, postType, description, taggedUsers, tags, PostStatus.ACTIVE, null, null);
    }

    public PostUpdateResult update(
            PostDescription description,
            PostTaggedUsers taggedUsers,
            PostTags tags) {
        Objects.requireNonNull(description, "description must not be null");
        Objects.requireNonNull(taggedUsers, "taggedUsers must not be null");
        Objects.requireNonNull(tags, "tags must not be null");

        if (this.description.equals(description) && this.taggedUsers.equals(taggedUsers) && this.tags.equals(tags)) {
            return new PostUpdateResult(this, false, Set.of());
        }

        Set<String> newlyTaggedUsers = new LinkedHashSet<>(taggedUsers.value());
        newlyTaggedUsers.removeAll(this.taggedUsers.value());

        return new PostUpdateResult(
                new Post(id, userId, collabId, postType, description, taggedUsers, tags, status, createdAt, updatedAt),
                true,
                newlyTaggedUsers
        );
    }

    public Post delete() {
        if (status != PostStatus.ACTIVE) {
            throw new PostNotActiveException(id.value(), status);
        }

        return new Post(id, userId, collabId, postType, description, taggedUsers, tags, PostStatus.DELETED,
                createdAt, updatedAt);
    }

    public PostId getId() {
        return id;
    }

    public UserId getUserId() {
        return userId;
    }

    public UUID getCollabId() {
        return collabId;
    }

    public PostType getPostType() {
        return postType;
    }

    public PostDescription getDescription() {
        return description;
    }

    public PostTaggedUsers getTaggedUsers() {
        return taggedUsers;
    }

    public PostTags getTags() {
        return tags;
    }

    public PostStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    private void validateCollabLink(PostType postType, UUID collabId) {
        if (postType == PostType.BASIC && collabId != null) {
            throw new IllegalArgumentException("Basic posts must not reference a collab");
        }
        if (postType == PostType.COLAB && collabId == null) {
            throw new IllegalArgumentException("Collab posts must reference a collab");
        }
    }
}
