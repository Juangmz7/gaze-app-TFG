package com.app.postcommandservice.post.infrastructure.repository;

import java.time.Instant;
import java.util.UUID;

import com.app.postcommandservice.post.domain.model.valueobj.PostStatus;

/** Closed projection over {@code posts} read before hard-deleting a never-accepted post (task 60). */
public interface PostDeletionView {

    UUID getId();

    UUID getUserId();

    UUID getCollabId();

    PostStatus getStatus();

    Instant getMediaPurgedAt();
}
