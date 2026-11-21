package com.app.postcommandservice.post.infrastructure.repository;

import java.time.Instant;
import java.util.UUID;

import com.app.postcommandservice.post.domain.model.valueobj.PostStatus;

/** Closed projection over {@code posts} for the media cleanup job's keyset pages (task 58). */
public interface PostMediaCleanupView {

    UUID getId();

    PostStatus getStatus();

    Instant getCreatedAt();
}
