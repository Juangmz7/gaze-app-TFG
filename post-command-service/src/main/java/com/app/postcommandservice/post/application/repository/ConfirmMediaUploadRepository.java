package com.app.postcommandservice.post.application.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.app.postcommandservice.post.domain.model.valueobj.MediaType;
import com.app.postcommandservice.post.domain.model.valueobj.PostStatus;

/**
 * Read-only access for the confirm-media-upload flow (task 35). Deliberately decoupled
 * from {@link PostRepository}: the confirm endpoint never loads or mutates the managed
 * {@code Post} aggregate, it only reads a projection of the fields it needs to validate
 * the request and build the outbound event.
 */
public interface ConfirmMediaUploadRepository {

    Optional<PendingMediaUpload> findById(UUID postId);

    record PendingMediaUpload(
            UUID postId,
            UUID authorId,
            PostStatus status,
            Instant createdAt,
            List<MediaItem> media
    ) {
    }

    record MediaItem(
            UUID id,
            String url,
            String thumbnailUrl,
            MediaType mediaType,
            int order
    ) {
    }
}
