package com.app.postcommandservice.post.application.repository;

import java.util.Optional;
import java.util.UUID;

public interface PostRequestIdempotencyRepository {

    void acquireCorrelationLock(UUID correlationId);

    Optional<UUID> findPostIdByCorrelationId(UUID correlationId);

    void save(UUID correlationId, UUID postId);
}
