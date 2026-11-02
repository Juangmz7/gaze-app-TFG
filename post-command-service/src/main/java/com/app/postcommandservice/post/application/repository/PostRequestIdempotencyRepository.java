package com.app.postcommandservice.post.application.repository;

import java.util.Optional;
import java.util.UUID;

public interface PostRequestIdempotencyRepository {

    void acquireCorrelationLock(UUID userId, UUID correlationId);

    Optional<ExistingIdempotencyRecord> find(UUID userId, UUID correlationId);

    void save(UUID userId, UUID correlationId, UUID postId, String requestHash);

    /**
     * Result of a prior request-idempotency lookup for a given (userId, correlationId) pair:
     * the previously created post id, and the hash of the payload that created it (nullable
     * for legacy rows persisted before payload hashing existed).
     */
    record ExistingIdempotencyRecord(UUID postId, String requestHash) {
    }
}
