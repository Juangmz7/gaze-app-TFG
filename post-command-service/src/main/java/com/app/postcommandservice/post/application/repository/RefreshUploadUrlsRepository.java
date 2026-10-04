package com.app.postcommandservice.post.application.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.app.postcommandservice.post.domain.model.valueobj.MediaType;
import com.app.postcommandservice.post.domain.model.valueobj.PostStatus;

/**
 * Read/write access for the refresh-upload-urls flow (task 38). Deliberately decoupled from
 * {@link PostRepository}, mirroring {@link ConfirmMediaUploadRepository} (task 35) and {@link
 * PostMediaVerificationRepository} (task 37): the read side returns a closed projection, never
 * the managed {@code Post} aggregate, and the write side persists only the per-media SAS
 * hash/expiry columns rather than a full aggregate save.
 *
 * <p>Hashes are never searchable (BCrypt is salted), so every write here is a direct, keyed
 * update by {@code mediaId} — never a scan-and-match over many rows.</p>
 */
public interface RefreshUploadUrlsRepository {

    Optional<RefreshableUpload> findById(UUID postId);

    /**
     * Persists a freshly signed upload SAS hash + expiry for {@code mediaId}'s content blob.
     */
    void updateUploadSas(UUID mediaId, String uploadSasHash, Instant uploadSasExpiresAt);

    /**
     * Persists a freshly signed upload SAS hash + expiry for {@code mediaId}'s thumbnail blob.
     * Only ever called for {@code VIDEO} media.
     */
    void updateThumbnailSas(UUID mediaId, String thumbnailSasHash, Instant thumbnailSasExpiresAt);

    record RefreshableUpload(
            UUID postId,
            UUID authorId,
            PostStatus status,
            Instant createdAt,
            List<MediaUploadState> media
    ) {
    }

    record MediaUploadState(
            UUID id,
            String url,
            String thumbnailUrl,
            MediaType mediaType,
            int order,
            String uploadSasHash,
            Instant uploadSasExpiresAt,
            String thumbnailSasHash,
            Instant thumbnailSasExpiresAt
    ) {
    }
}
