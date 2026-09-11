package com.app.postcommandservice.post.domain.model;

import java.util.Objects;
import java.net.URI;
import java.util.UUID;

import com.app.postcommandservice.post.domain.model.valueobj.MediaType;
import com.app.postcommandservice.post.domain.exception.InvalidPostMediaException;

public record PostMedia(UUID id, String url, String thumbnailUrl, MediaType mediaType, Integer duration, int order) {
    public PostMedia {
        if (id == null) throw new InvalidPostMediaException("media id must not be null");
        if (url == null || url.isBlank()) {
            throw new InvalidPostMediaException("media url must not be blank");
        }
        validateAbsoluteHttpUri(url, "media url");
        if (thumbnailUrl != null) {
            validateAbsoluteHttpUri(thumbnailUrl, "thumbnail url");
        }
        if (mediaType == null) throw new InvalidPostMediaException("mediaType must not be null");
        if (order < 1) {
            throw new InvalidPostMediaException("media order must start at 1");
        }
        if (duration != null && duration < 1) {
            throw new InvalidPostMediaException("media duration must be positive");
        }
        if (mediaType == MediaType.IMAGE && duration != null) {
            throw new InvalidPostMediaException("image duration must be null");
        }
    }

    private static void validateAbsoluteHttpUri(String value, String field) {
        try {
            URI uri = URI.create(value);
            if ((!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) || uri.getHost() == null) {
                throw new InvalidPostMediaException(field + " must be an absolute HTTP(S) URI");
            }
        } catch (IllegalArgumentException exception) {
            throw new InvalidPostMediaException(field + " must be an absolute HTTP(S) URI");
        }
    }
}
