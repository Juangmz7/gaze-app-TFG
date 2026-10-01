package com.app.postcommandservice.post.domain.model;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.springframework.util.StringUtils;

import com.app.postcommandservice.post.domain.exception.InvalidPostMediaException;
import com.app.postcommandservice.post.domain.model.valueobj.MediaType;
import com.app.postcommandservice.post.domain.model.valueobj.PostTaggedUsers;

public class PostMedia {

    private final UUID id;
    private final UUID postId;
    private final String url;
    private final String thumbnailUrl;
    private final MediaType mediaType;
    private final Integer duration;
    private final Set<String> taggedUsers;
    private final int order;

    public PostMedia(
            UUID id,
            UUID postId,
            String url,
            String thumbnailUrl,
            MediaType mediaType,
            Integer duration,
            Set<String> taggedUsers,
            int order) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.postId = Objects.requireNonNull(postId, "postId must not be null");
        this.mediaType = Objects.requireNonNull(mediaType, "mediaType must not be null");

        if (!StringUtils.hasText(url)) {
            throw new InvalidPostMediaException("Post media url must not be blank");
        }
        if (mediaType == MediaType.IMAGE && duration != null) {
            throw new InvalidPostMediaException("Post media duration must be null for IMAGE media type");
        }
        if (duration != null && duration < 0) {
            throw new InvalidPostMediaException("Post media duration must not be negative");
        }
        if (order < 1) {
            throw new InvalidPostMediaException("Post media order must be a positive 1-based index");
        }

        this.url = url;
        this.thumbnailUrl = mediaType == MediaType.IMAGE ? url : thumbnailUrl;
        this.duration = duration;
        this.taggedUsers = new PostTaggedUsers(taggedUsers == null ? Set.of() : taggedUsers).value();
        this.order = order;
    }

    public static PostMedia create(
            UUID postId,
            String url,
            String thumbnailUrl,
            MediaType mediaType,
            Integer duration,
            Set<String> taggedUsers,
            int order) {
        return new PostMedia(UUID.randomUUID(), postId, url, thumbnailUrl, mediaType, duration, taggedUsers, order);
    }

    public UUID getId() {
        return id;
    }

    public UUID getPostId() {
        return postId;
    }

    public String getUrl() {
        return url;
    }

    public String getThumbnailUrl() {
        return thumbnailUrl;
    }

    public MediaType getMediaType() {
        return mediaType;
    }

    public Integer getDuration() {
        return duration;
    }

    public Set<String> getTaggedUsers() {
        return taggedUsers;
    }

    public int getOrder() {
        return order;
    }
}
