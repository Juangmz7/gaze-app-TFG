package com.app.postcommandservice.view.application.repository;

import java.util.Optional;
import java.util.UUID;

import com.app.postcommandservice.view.domain.model.PostView;

public interface PostViewRepository {

    long countByPostIdAndUserId(UUID postId, UUID userId);

    Optional<PostView> findById(UUID viewId);

    /**
     * Inserts the view using its client-generated viewId as primary key. Never updates an existing row.
     *
     * @return {@code true} when the row was inserted, {@code false} when a view with the same id already
     *         existed (duplicate key, including one committed concurrently by another delivery).
     */
    boolean insertIfAbsent(PostView postView);
}
