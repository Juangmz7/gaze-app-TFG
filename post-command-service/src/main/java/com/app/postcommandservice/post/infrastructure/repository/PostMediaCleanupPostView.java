package com.app.postcommandservice.post.infrastructure.repository;

import java.time.Instant;
import java.util.UUID;

import com.app.postcommandservice.post.domain.model.valueobj.PostStatus;

/**
 * Closed Spring Data JPA projection (task 39) over a candidate post for the scheduled
 * media-cleanup job: never the managed {@code PostEntity}, so no lazy-loading trap and no
 * connection held past the read-only selection transaction.
 */
public interface PostMediaCleanupPostView {

    UUID getId();

    PostStatus getStatus();

    Instant getCreatedAt();
}
