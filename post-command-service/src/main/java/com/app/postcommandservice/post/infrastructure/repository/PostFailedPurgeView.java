package com.app.postcommandservice.post.infrastructure.repository;

import java.time.Instant;
import java.util.UUID;

/** Closed projection over {@code posts} for the failed-post purge job's keyset pages (task 60). */
public interface PostFailedPurgeView {

    UUID getId();

    Instant getMediaPurgedAt();
}
