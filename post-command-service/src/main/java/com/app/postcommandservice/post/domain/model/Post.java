package com.app.postcommandservice.post.domain.model;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

import com.app.postcommandservice.post.domain.model.valueobj.PostDescription;
import com.app.postcommandservice.post.domain.model.valueobj.PostId;
import com.app.postcommandservice.post.domain.model.valueobj.PostStatus;
import com.app.postcommandservice.post.domain.model.valueobj.PostTaggedUsers;
import com.app.postcommandservice.post.domain.model.valueobj.PostTags;
import com.app.postcommandservice.shared.domain.model.user.valueobj.UserId;

public class Post {

    private final PostId id;
    private final UserId userId;
    private final PostDescription description;
    private final PostTaggedUsers taggedUsers;
    private final PostTags tags;
    private final PostStatus status;
    private final Instant createdAt;
    private final Instant updatedAt;

    public Post(
            PostId id,
            UserId userId,
            PostDescription description,
            PostTaggedUsers taggedUsers,
            PostTags tags,
            PostStatus status,
            Instant createdAt,
            Instant updatedAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.userId = Objects.requireNonNull(userId, "userId must not be null");
        this.description = Objects.requireNonNull(description, "description must not be null");
        this.taggedUsers = Objects.requireNonNull(taggedUsers, "taggedUsers must not be null");
        this.tags = Objects.requireNonNull(tags, "tags must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static Post create(
            PostId id,
            UserId userId,
            PostDescription description,
            PostTaggedUsers taggedUsers,
            PostTags tags) {
        return new Post(id, userId, description, taggedUsers, tags, PostStatus.ACTIVE, null, null);
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
                new Post(id, userId, description, taggedUsers, tags, status, createdAt, updatedAt),
                true,
                newlyTaggedUsers
        );
    }

    public PostId getId() {
        return id;
    }

    public UserId getUserId() {
        return userId;
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
}
