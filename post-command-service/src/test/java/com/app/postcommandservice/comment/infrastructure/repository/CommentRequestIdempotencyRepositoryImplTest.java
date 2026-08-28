package com.app.postcommandservice.comment.infrastructure.repository;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.app.postcommandservice.comment.infrastructure.entity.CommentRequestIdempotencyEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CommentRequestIdempotencyRepositoryImplTest {

    @Mock
    private CommentRequestIdempotencyJpaRepository commentRequestIdempotencyJpaRepository;

    @Mock
    private EntityManager entityManager;

    @Mock
    private Query query;

    private CommentRequestIdempotencyRepositoryImpl repository;

    @BeforeEach
    void setUp() {
        repository = new CommentRequestIdempotencyRepositoryImpl(commentRequestIdempotencyJpaRepository, entityManager);
    }

    @Test
    void shouldAcquireTransactionScopedCorrelationLock() {
        var correlationId = UUID.randomUUID();

        when(entityManager.createNativeQuery(
                "SELECT pg_advisory_xact_lock(CAST(hashtext(CAST(:correlationId AS text)) AS bigint))"
        )).thenReturn(query);
        when(query.setParameter("correlationId", correlationId.toString())).thenReturn(query);

        repository.acquireCorrelationLock(correlationId);

        verify(query).getSingleResult();
    }

    @Test
    void shouldSaveAndResolveCommentIdByCorrelationId() {
        var correlationId = UUID.randomUUID();
        var commentId = UUID.randomUUID();
        var entity = new CommentRequestIdempotencyEntity(correlationId, commentId, null);

        when(commentRequestIdempotencyJpaRepository.findById(correlationId)).thenReturn(Optional.of(entity));

        repository.save(correlationId, commentId);

        verify(commentRequestIdempotencyJpaRepository).save(any(CommentRequestIdempotencyEntity.class));
        assertThat(repository.findCommentIdByCorrelationId(correlationId)).contains(commentId);
    }
}
