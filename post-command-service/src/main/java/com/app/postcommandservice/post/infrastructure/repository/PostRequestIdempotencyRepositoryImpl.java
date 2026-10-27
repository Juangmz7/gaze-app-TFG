package com.app.postcommandservice.post.infrastructure.repository;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import com.app.postcommandservice.post.application.repository.PostRequestIdempotencyRepository;
import com.app.postcommandservice.post.infrastructure.entity.PostRequestIdempotencyEntity;
import com.app.postcommandservice.post.infrastructure.entity.PostRequestIdempotencyId;

@Repository
@RequiredArgsConstructor
public class PostRequestIdempotencyRepositoryImpl implements PostRequestIdempotencyRepository {

    private final PostRequestIdempotencyJpaRepository postRequestIdempotencyJpaRepository;
    private final EntityManager entityManager;

    @Override
    public void acquireCorrelationLock(UUID userId, UUID correlationId) {
        String lockKey = userId.toString() + ":" + correlationId.toString();
        entityManager.createNativeQuery(
                        "SELECT pg_advisory_xact_lock(hashtextextended(:lockKey, 0))"
                )
                .setParameter("lockKey", lockKey)
                .getSingleResult();
    }

    @Override
    public Optional<ExistingIdempotencyRecord> find(UUID userId, UUID correlationId) {
        return postRequestIdempotencyJpaRepository
                .findById(new PostRequestIdempotencyId(userId, correlationId))
                .map(entity -> new ExistingIdempotencyRecord(entity.getPostId(), entity.getRequestHash()));
    }

    @Override
    public void save(UUID userId, UUID correlationId, UUID postId, String requestHash) {
        postRequestIdempotencyJpaRepository.save(new PostRequestIdempotencyEntity(
                new PostRequestIdempotencyId(userId, correlationId),
                postId,
                requestHash,
                null
        ));
    }
}
