package com.app.postcommandservice.post.application.repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.app.postcommandservice.post.domain.model.valueobj.MediaType;
import com.app.postcommandservice.post.domain.model.valueobj.PostStatus;

/**
 * Read/write access for the async media-upload verification flow (task 37). Deliberately
 * decoupled from {@link PostRepository}: the read side never loads the managed {@code Post}
 * aggregate (to avoid holding a connection or a lazy-loading trap across the {@code
 * MediaVerifier} call), and the write side performs a single conditional status update rather
 * than a full aggregate save, so a duplicate/late message safely affects zero rows instead of
 * overwriting a status set by a concurrent message or by task 39's cleanup.
 */
public interface PostMediaVerificationRepository {

    /**
     * Immutable projection of the fields {@code PostMediaVerificationService} needs to decide
     * whether to proceed: the current status and each media item's id/type. Never a managed
     * entity, so it stays valid after the owning transaction closes.
     */
    Optional<PostSnapshot> findSnapshot(UUID postId);

    /**
     * Atomically transitions {@code postId} to {@code newStatus} only if its current status is
     * still {@code PENDING}. Returns the number of affected rows (0 or 1); 0 means the post was
     * no longer {@code PENDING} (duplicate message, or expired/handled concurrently) and the
     * caller must not publish any outcome event.
     */
    int updateStatusIfPending(UUID postId, PostStatus newStatus);

    /**
     * Persists the extracted duration (milliseconds) for each {@code VIDEO} media item. Must
     * only be called after {@link #updateStatusIfPending} reports a successful transition.
     */
    void persistVideoDurationsMillis(Map<UUID, Integer> durationsMillis);

    record PostSnapshot(UUID postId, PostStatus status, List<MediaSnapshot> media) {
    }

    record MediaSnapshot(UUID id, MediaType mediaType) {
    }
}
