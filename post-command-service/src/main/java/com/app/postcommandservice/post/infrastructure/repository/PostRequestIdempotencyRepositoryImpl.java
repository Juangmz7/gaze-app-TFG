package com.app.postcommandservice.post.infrastructure.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import com.app.postcommandservice.post.application.repository.PostRequestIdempotencyRepository;
import com.app.postcommandservice.post.infrastructure.entity.PostRequestIdempotencyEntity;

@Repository
@RequiredArgsConstructor
public class PostRequestIdempotencyRepositoryImpl implements PostRequestIdempotencyRepository {

    private final PostRequestIdempotencyJpaRepository postRequestIdempotencyJpaRepository;
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
    public Optional<UUID> findPostIdByCorrelationId(UUID correlationId) {
        return postRequestIdempotencyJpaRepository.findById(correlationId).map(PostRequestIdempotencyEntity::getPostId);
    }

    @Override
    public void save(UUID correlationId, UUID postId) {
        postRequestIdempotencyJpaRepository.save(new PostRequestIdempotencyEntity(correlationId, postId, Instant.now()));
    }
}
