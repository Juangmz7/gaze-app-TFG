package com.app.postcommandservice.post.application.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.app.postcommandservice.post.domain.model.valueobj.MediaType;
import com.app.postcommandservice.post.domain.model.valueobj.PostStatus;

/**
 * Read/write access for the scheduled media-cleanup job (task 39). Deliberately decoupled
 * from {@link PostRepository}, mirroring {@code PostMediaVerificationRepository} (task 37):
 * the read side never loads the managed {@code Post} aggregate, and every write is a single
 * conditional {@code UPDATE} rather than a full aggregate load-mutate-save, so a duplicate
 * run (or a race with task 37's listener accepting the post concurrently) safely affects zero
 * rows instead of overwriting a status/timestamp set elsewhere.
 */
public interface PostMediaCleanupRepository {

    /**
     * Keyset-paginated selection of up to {@code batchSize} posts whose media upload was never
     * confirmed within {@code expiryThreshold} (status {@code PENDING}) or was already
     * rejected (status {@code MEDIA_UPLOAD_FAILED}), and that have not yet been purged.
     * {@code cursor} is {@code null} for the first page of a run; otherwise results start
     * strictly after it in {@code (createdAt, id)} order.
     */
    List<ExpiredPost> findExpiredBatch(Instant expiryThreshold, KeysetCursor cursor, int batchSize);

    /**
     * Atomically transitions {@code postId} from {@code PENDING} to {@code
     * MEDIA_UPLOAD_FAILED}. Returns the number of affected rows (0 or 1); 0 means the post was
     * no longer {@code PENDING} (task 37's listener accepted or failed it concurrently) and
     * the caller must not publish an event or delete any blob.
     */
    int transitionPendingToFailed(UUID postId);

    /**
     * Marks {@code postId} as fully purged at {@code purgedAt}, only if it had not already
     * been marked. Returns the number of affected rows (0 or 1).
     */
    int markMediaPurged(UUID postId, Instant purgedAt);

    record KeysetCursor(Instant createdAt, UUID id) {
    }

    record ExpiredPost(UUID postId, PostStatus status, Instant createdAt, List<MediaBlobRef> mediaBlobs) {
    }

    record MediaBlobRef(UUID mediaId, MediaType mediaType, String url, String thumbnailUrl) {
    }
}
