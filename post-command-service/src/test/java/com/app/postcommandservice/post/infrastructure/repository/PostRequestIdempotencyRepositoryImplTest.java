package com.app.postcommandservice.post.infrastructure.repository;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.app.postcommandservice.post.infrastructure.entity.PostRequestIdempotencyEntity;
import com.app.postcommandservice.post.infrastructure.entity.PostRequestIdempotencyId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PostRequestIdempotencyRepositoryImplTest {

    @Mock
    private PostRequestIdempotencyJpaRepository postRequestIdempotencyJpaRepository;

    @Mock
    private EntityManager entityManager;

    @Mock
    private Query query;

    private PostRequestIdempotencyRepositoryImpl repository;

    @BeforeEach
    void setUp() {
        repository = new PostRequestIdempotencyRepositoryImpl(postRequestIdempotencyJpaRepository, entityManager);
    }

    @Test
    void shouldAcquireTransactionScopedCorrelationLockKeyedByUserAndCorrelation() {
        var userId = UUID.randomUUID();
        var correlationId = UUID.randomUUID();
        var expectedLockKey = userId + ":" + correlationId;

        when(entityManager.createNativeQuery(
                "SELECT pg_advisory_xact_lock(hashtextextended(:lockKey, 0))"
        )).thenReturn(query);
        when(query.setParameter("lockKey", expectedLockKey)).thenReturn(query);

        repository.acquireCorrelationLock(userId, correlationId);

        verify(query).getSingleResult();
    }

    @Test
    void shouldSaveAndResolveExistingRecordByUserAndCorrelationId() {
        var userId = UUID.randomUUID();
        var correlationId = UUID.randomUUID();
        var postId = UUID.randomUUID();
        var requestHash = "a".repeat(64);
        var entity = new PostRequestIdempotencyEntity(
                new PostRequestIdempotencyId(userId, correlationId), postId, requestHash, null);

        when(postRequestIdempotencyJpaRepository.findById(new PostRequestIdempotencyId(userId, correlationId)))
                .thenReturn(Optional.of(entity));

        repository.save(userId, correlationId, postId, requestHash);

        verify(postRequestIdempotencyJpaRepository).save(any(PostRequestIdempotencyEntity.class));
        assertThat(repository.find(userId, correlationId))
                .contains(new com.app.postcommandservice.post.application.repository
                        .PostRequestIdempotencyRepository.ExistingIdempotencyRecord(postId, requestHash));
    }

    @Test
    void shouldResolveNoRecordWhenNoneExistsForUserAndCorrelationId() {
        var userId = UUID.randomUUID();
        var correlationId = UUID.randomUUID();

        when(postRequestIdempotencyJpaRepository.findById(new PostRequestIdempotencyId(userId, correlationId)))
                .thenReturn(Optional.empty());

        assertThat(repository.find(userId, correlationId)).isEmpty();
    }
}
