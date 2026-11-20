package com.app.postcommandservice.post.application.repository;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.app.postcommandservice.post.domain.model.valueobj.PostStatus;

/**
 * Writes behind the hard delete of a post that never reached {@code ACCEPTED} (task 60). The
 * post itself goes with one conditional {@code DELETE}, so a post that media verification accepts
 * concurrently is never removed; the rows without a foreign key to {@code posts} (request
 * idempotency, and the collab created with the post) are removed explicitly.
 */
public interface UnacceptedPostDeletionRepository {

    /** The post's owner, collab, status and blob urls, whatever its status; empty if it does not exist. */
    Optional<PostDeletionSnapshot> findSnapshot(UUID postId);

    /**
     * Deletes the post if its status is still {@code PENDING} or {@code MEDIA_UPLOAD_FAILED}
     * (media, tagged users and tags go by cascade). Returns {@code false}, deleting nothing,
     * when the post was accepted or removed meanwhile. Always flushes and clears the persistence
     * context, whatever the result, so later reads in the same transaction see the database state.
     */
    boolean deleteIfUnaccepted(UUID postId);

    /**
     * Removes the request-idempotency records that point at the post: its
     * {@code post_request_idempotency} row and, for a post created with its collab, its
     * {@code collab_request_idempotency} row. A dangling record would make a retried create
     * return a deleted post.
     */
    void deleteRequestIdempotency(UUID postId);

    /** Whether any post (whatever its status) still references the collab. */
    boolean collabHasPosts(UUID collabId);

    /** Hard-deletes the collab, its members and its request-idempotency row. */
    void deleteCollab(UUID collabId);

    /**
     * @param blobUrls distinct, non-blank blob urls of the post's media; empty once the media
     *                 cleanup job purged them ({@code media_purged_at} set)
     */
    record PostDeletionSnapshot(UUID postId, UUID userId, UUID collabId, PostStatus status, List<String> blobUrls) {

        public PostDeletionSnapshot {
            Objects.requireNonNull(postId, "postId must not be null");
            Objects.requireNonNull(userId, "userId must not be null");
            Objects.requireNonNull(status, "status must not be null");
            blobUrls = blobUrls == null ? List.of() : List.copyOf(blobUrls);
        }
    }
}
