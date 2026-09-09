package com.app.postcommandservice.post.domain.model;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.List;
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
    private final PostInfo info;
    private final List<PostMedia> media;
    private final PostStatus status;
    private final Instant createdAt;
    private final Instant updatedAt;

    public Post(
            PostId id,
            UserId userId,
            UUID collabId,
            PostInfo info,
            List<PostMedia> media,
            PostStatus status,
            Instant createdAt,
            Instant updatedAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.userId = Objects.requireNonNull(userId, "userId must not be null");
        this.info = Objects.requireNonNull(info, "info must not be null");
        this.media = List.copyOf(Objects.requireNonNull(media, "media must not be null"));
        this.status = Objects.requireNonNull(status, "status must not be null");
        validateCollabLink(info.postType(), collabId);
        validateMedia(this.media, status);
        this.collabId = collabId;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static Post create(
            PostId id,
            UserId userId,
            UUID collabId,
            PostInfo info, List<PostMedia> media) {
        return new Post(id, userId, collabId, info, media, PostStatus.ACTIVE, null, null);
    }

    public static Post create(PostId id, UserId userId, UUID collabId, PostType postType,
                              PostDescription description, PostTaggedUsers taggedUsers, PostTags tags,
                              List<PostMedia> media) {
        return create(id, userId, collabId, new PostInfo(null, description, taggedUsers, tags, postType), media);
    }

    public PostUpdateResult update(
            PostInfo info, List<PostMedia> media) {
        if (this.info.equals(info) && this.media.equals(media)) {
            return new PostUpdateResult(this, false, Set.of());
        }

        Set<String> newlyTaggedUsers = new LinkedHashSet<>(info.taggedUsers().value());
        newlyTaggedUsers.removeAll(this.info.taggedUsers().value());

        return new PostUpdateResult(
                new Post(id, userId, collabId, info, media, status, createdAt, updatedAt),
                true,
                newlyTaggedUsers
        );
    }

    public Post delete() {
        if (status != PostStatus.ACTIVE) {
            throw new PostNotActiveException(id.value(), status);
        }

        return new Post(id, userId, collabId, info, List.of(), PostStatus.DELETED,
                createdAt, updatedAt);
    }

    public Post linkToCollab(UUID targetCollabId) {
        Objects.requireNonNull(targetCollabId, "targetCollabId must not be null");

        return new Post(id, userId, targetCollabId, new PostInfo(info.title(), info.description(), info.taggedUsers(), info.tags(), PostType.COLAB), media, status, createdAt, updatedAt);
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
        return info.postType();
    }

    public PostDescription getDescription() {
        return info.description();
    }

    public PostTaggedUsers getTaggedUsers() {
        return info.taggedUsers();
    }

    public PostTags getTags() {
        return info.tags();
    }

    public PostInfo getInfo() { return info; }
    public List<PostMedia> getMedia() { return media; }

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

    private void validateMedia(List<PostMedia> items, PostStatus status) {
        if (status != PostStatus.DELETED && items.isEmpty()) {
            throw new IllegalArgumentException("Active posts must contain at least one media item");
        }
        var orders = items.stream().map(PostMedia::order).sorted().toList();
        for (int index = 0; index < orders.size(); index++) {
            if (orders.get(index) != index + 1) {
                throw new IllegalArgumentException("media orders must be contiguous from 1");
            }
        }
    }
}
