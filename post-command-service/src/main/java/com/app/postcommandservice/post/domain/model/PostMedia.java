package com.app.postcommandservice.post.domain.model;

import java.util.Objects;
import java.util.UUID;

import com.app.postcommandservice.post.domain.model.valueobj.MediaType;
import com.app.postcommandservice.post.domain.exception.InvalidPostMediaException;

public record PostMedia(UUID id, String url, String thumbnailUrl, MediaType mediaType, Integer duration, int order) {
    public PostMedia {
        if (id == null) throw new InvalidPostMediaException("media id must not be null");
        if (url == null || url.isBlank()) {
            throw new InvalidPostMediaException("media url must not be blank");
        }
        if (mediaType == null) throw new InvalidPostMediaException("mediaType must not be null");
        if (order < 1) {
            throw new InvalidPostMediaException("media order must start at 1");
        }
        if (duration != null && duration < 0) {
            throw new InvalidPostMediaException("media duration must not be negative");
        }
        if (mediaType == MediaType.IMAGE && duration != null) {
            throw new InvalidPostMediaException("image duration must be null");
        }
    }
}
