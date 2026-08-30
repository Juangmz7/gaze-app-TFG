package com.app.postcommandservice.collab.application.repository;

import java.util.Optional;
import java.util.UUID;

public interface CollabRequestIdempotencyRepository {

    void acquireCorrelationLock(UUID correlationId);

    Optional<UUID> findEntityIdByCorrelationId(UUID correlationId);

    void save(UUID correlationId, UUID entityId);
}
