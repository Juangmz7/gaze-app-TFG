package com.app.postcommandservice.post.application.repository;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.app.postcommandservice.post.domain.model.valueobj.PostStatus;

/**
 * Read/write access for the scheduled media cleanup job (task 58). Like
 * {@link PostMediaVerificationRepository} it never loads the managed {@code Post} aggregate: the
 * read side returns immutable projections and every write is a single conditional {@code UPDATE},
 * so a post the upload listener accepts concurrently is never overwritten.
 */
public interface PostMediaCleanupRepository {

    /**
     * One keyset page of posts whose upload expired: status {@code PENDING} or
     * {@code MEDIA_UPLOAD_FAILED}, created before {@code createdBefore}, and whose blobs were not
     * purged yet, ordered by {@code (createdAt, id)}. {@code after} is the last position of the
     * previous page, or {@code null} for the first page.
     */
    List<ExpiredUpload> findExpiredUploads(Instant createdBefore, CleanupCursor after, int limit);

    /**
     * Atomically moves the post from {@code PENDING} to {@code MEDIA_UPLOAD_FAILED}. Returns
     * {@code false} when the post was no longer {@code PENDING} (the upload listener resolved it
     * meanwhile), in which case the caller must leave the post and its blobs alone.
     */
    boolean failIfPending(UUID postId);

    /**
     * Records that the blobs of a {@code MEDIA_UPLOAD_FAILED} post were deleted. Returns
     * {@code false} if the post was already purged or is not {@code MEDIA_UPLOAD_FAILED}.
     */
    boolean markMediaPurged(UUID postId, Instant purgedAt);

    record ExpiredUpload(UUID postId, PostStatus status, Instant createdAt, List<MediaBlobs> media) {

        public ExpiredUpload {
            Objects.requireNonNull(postId, "postId must not be null");
            Objects.requireNonNull(status, "status must not be null");
            Objects.requireNonNull(createdAt, "createdAt must not be null");
            media = media == null ? List.of() : List.copyOf(media);
        }

        public CleanupCursor cursor() {
            return new CleanupCursor(createdAt, postId);
        }
    }

    /** Blob urls of one media item; for IMAGE media both urls point to the same blob. */
    record MediaBlobs(String url, String thumbnailUrl) {
    }

    record CleanupCursor(Instant createdAt, UUID postId) {

        public CleanupCursor {
            Objects.requireNonNull(createdAt, "createdAt must not be null");
            Objects.requireNonNull(postId, "postId must not be null");
        }
    }
}
