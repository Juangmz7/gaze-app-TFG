package com.app.postcommandservice.comment.infrastructure.repository;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import com.app.postcommandservice.comment.application.repository.CommentRequestIdempotencyRepository;
import com.app.postcommandservice.comment.infrastructure.entity.CommentRequestIdempotencyEntity;

@Repository
@RequiredArgsConstructor
public class CommentRequestIdempotencyRepositoryImpl implements CommentRequestIdempotencyRepository {

    private final CommentRequestIdempotencyJpaRepository commentRequestIdempotencyJpaRepository;
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
    public Optional<UUID> findCommentIdByCorrelationId(UUID correlationId) {
        return commentRequestIdempotencyJpaRepository.findById(correlationId)
                .map(CommentRequestIdempotencyEntity::getCommentId);
    }

    @Override
    public void save(UUID correlationId, UUID commentId) {
        commentRequestIdempotencyJpaRepository.save(new CommentRequestIdempotencyEntity(correlationId, commentId, null));
    }
}
