package com.app.postcommandservice.post.domain.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.app.postcommandservice.post.domain.exception.InvalidPostMediaException;
import com.app.postcommandservice.post.domain.exception.PostNotAcceptedException;
import com.app.postcommandservice.post.domain.exception.PostNotPendingException;
import com.app.postcommandservice.post.domain.model.valueobj.PostId;
import com.app.postcommandservice.post.domain.model.valueobj.PostStatus;
import com.app.postcommandservice.post.domain.model.valueobj.PostType;
import com.app.postcommandservice.shared.domain.model.user.valueobj.UserId;

public class Post {

    private final PostId id;
    private final UserId userId;
    private final UUID collabId;
    private final PostInfo postInfo;
    private final List<PostMedia> media;
    private final PostStatus status;
    private final Instant createdAt;
    private final Instant updatedAt;

    public Post(
            PostId id,
            UserId userId,
            UUID collabId,
            PostInfo postInfo,
            List<PostMedia> media,
            PostStatus status,
            Instant createdAt,
            Instant updatedAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.userId = Objects.requireNonNull(userId, "userId must not be null");
        this.postInfo = Objects.requireNonNull(postInfo, "postInfo must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
        validateCollabLink(postInfo.postType(), collabId);
        this.collabId = collabId;
        this.media = validateMedia(status, media);
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static Post create(
            PostId id,
            UserId userId,
            UUID collabId,
            PostInfo postInfo,
            List<PostMedia> media) {
        return new Post(id, userId, collabId, postInfo, media, PostStatus.ACCEPTED, null, null);
    }

    /**
     * Creates a post awaiting client media upload confirmation (task 33). Media urls must
     * already be server-generated at this point; {@code duration} stays {@code null} until
     * the upload is confirmed and the post transitions to {@code ACCEPTED} (task 37).
     */
    public static Post createPending(
            PostId id,
            UserId userId,
            UUID collabId,
            PostInfo postInfo,
            List<PostMedia> media) {
        return new Post(id, userId, collabId, postInfo, media, PostStatus.PENDING, null, null);
    }

    public PostUpdateResult update(PostInfo newPostInfo) {
        Objects.requireNonNull(newPostInfo, "postInfo must not be null");

        if (this.postInfo.equals(newPostInfo)) {
            return new PostUpdateResult(this, false);
        }

        return new PostUpdateResult(
                new Post(id, userId, collabId, newPostInfo, media, status, createdAt, updatedAt),
                true
        );
    }

    public Post delete() {
        if (status != PostStatus.ACCEPTED) {
            throw new PostNotAcceptedException(id.value(), status);
        }

        return new Post(id, userId, collabId, postInfo, List.of(), PostStatus.DELETED, createdAt, updatedAt);
    }

    public Post linkToCollab(UUID targetCollabId) {
        Objects.requireNonNull(targetCollabId, "targetCollabId must not be null");

        var linkedInfo = new PostInfo(
                postInfo.description(),
                postInfo.tags(),
                PostType.COLLAB
        );
        return new Post(id, userId, targetCollabId, linkedInfo, media, status, createdAt, updatedAt);
    }

    public Post acceptMediaUpload() {
        requirePending("accept the media upload");
        return new Post(id, userId, collabId, postInfo, media, PostStatus.ACCEPTED, createdAt, updatedAt);
    }

    public Post failMediaUpload() {
        requirePending("fail the media upload");
        return new Post(id, userId, collabId, postInfo, media, PostStatus.MEDIA_UPLOAD_FAILED, createdAt, updatedAt);
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

    public PostInfo getPostInfo() {
        return postInfo;
    }

    public List<PostMedia> getMedia() {
        return media;
    }

    public PostType getPostType() {
        return postInfo.postType();
    }

    public com.app.postcommandservice.post.domain.model.valueobj.PostDescription getDescription() {
        return postInfo.description();
    }

    public com.app.postcommandservice.post.domain.model.valueobj.PostTags getTags() {
        return postInfo.tags();
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

    private void requirePending(String action) {
        if (status != PostStatus.PENDING) {
            throw new PostNotPendingException(id.value(), status, action);
        }
    }

    private void validateCollabLink(PostType postType, UUID collabId) {
        if (postType == PostType.BASIC && collabId != null) {
            throw new IllegalArgumentException("Basic posts must not reference a collab");
        }
        if (postType == PostType.COLLAB && collabId == null) {
            throw new IllegalArgumentException("Collab posts must reference a collab");
        }
    }

    private static List<PostMedia> validateMedia(PostStatus status, List<PostMedia> media) {
        Objects.requireNonNull(media, "media must not be null");

        if (status == PostStatus.DELETED) {
            return List.copyOf(media);
        }

        if (media.isEmpty()) {
            throw new InvalidPostMediaException("Post must contain at least one media item");
        }

        Set<Integer> orders = new HashSet<>();
        for (PostMedia postMedia : media) {
            if (!orders.add(postMedia.getOrder())) {
                throw new InvalidPostMediaException("Post media order values must be distinct");
            }
        }

        int size = media.size();
        for (int order = 1; order <= size; order++) {
            if (!orders.contains(order)) {
                throw new InvalidPostMediaException(
                        "Post media order values must be contiguous starting at 1, without gaps");
            }
        }

        return List.copyOf(new ArrayList<>(media));
    }
}
