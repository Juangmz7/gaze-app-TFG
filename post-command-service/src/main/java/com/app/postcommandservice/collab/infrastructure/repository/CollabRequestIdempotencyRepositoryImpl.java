package com.app.postcommandservice.collab.infrastructure.repository;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import com.app.postcommandservice.collab.application.repository.CollabRequestIdempotencyRepository;
import com.app.postcommandservice.collab.infrastructure.entity.CollabRequestIdempotencyEntity;

@Repository
@RequiredArgsConstructor
public class CollabRequestIdempotencyRepositoryImpl implements CollabRequestIdempotencyRepository {

    private final CollabRequestIdempotencyJpaRepository collabRequestIdempotencyJpaRepository;
    private final EntityManager entityManager;

    @Override
    public void acquireCorrelationLock(UUID correlationId) {
        entityManager.createNativeQuery(
                        "SELECT pg_advisory_xact_lock(CAST(hashtext(CAST(:correlationId AS text)) AS bigint))"
                )
                .setParameter("correlationId", correlationId.toString())
                .getSingleResult();
    }

    @Override
    public Optional<UUID> findEntityIdByCorrelationId(UUID correlationId) {
        return collabRequestIdempotencyJpaRepository.findById(correlationId)
                .map(CollabRequestIdempotencyEntity::getEntityId);
    }

    @Override
    public void save(UUID correlationId, UUID entityId) {
        collabRequestIdempotencyJpaRepository.save(new CollabRequestIdempotencyEntity(correlationId, entityId, null));
    }
}
