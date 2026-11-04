package com.app.postcommandservice.post.infrastructure.repository;

import java.time.Instant;
import java.util.UUID;

import com.app.postcommandservice.post.domain.model.valueobj.PostStatus;

/**
 * Closed Spring Data JPA projection (task 35): the backing {@code @Query} selects only
 * these columns, so no managed {@code PostEntity} is ever loaded for the
 * confirm-media-upload read.
 */
public interface PostConfirmMediaUploadView {

    UUID getId();

    UUID getAuthorId();

    PostStatus getStatus();

    Instant getCreatedAt();
}
