package com.app.postcommandservice.post.application.repository;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Read side of the scheduled purge of failed posts (task 60). */
public interface FailedPostPurgeRepository {

    /**
     * One keyset page of {@code MEDIA_UPLOAD_FAILED} posts whose blobs were purged before
     * {@code purgedBefore}, ordered by {@code (mediaPurgedAt, id)}. {@code after} is the last
     * position of the previous page, or {@code null} for the first page.
     */
    List<PurgeablePost> findPurgeablePosts(Instant purgedBefore, PurgeCursor after, int limit);

    record PurgeablePost(UUID postId, Instant mediaPurgedAt) {

        public PurgeablePost {
            Objects.requireNonNull(postId, "postId must not be null");
            Objects.requireNonNull(mediaPurgedAt, "mediaPurgedAt must not be null");
        }

        public PurgeCursor cursor() {
            return new PurgeCursor(mediaPurgedAt, postId);
        }
    }

    record PurgeCursor(Instant mediaPurgedAt, UUID postId) {

        public PurgeCursor {
            Objects.requireNonNull(mediaPurgedAt, "mediaPurgedAt must not be null");
            Objects.requireNonNull(postId, "postId must not be null");
        }
    }
}
