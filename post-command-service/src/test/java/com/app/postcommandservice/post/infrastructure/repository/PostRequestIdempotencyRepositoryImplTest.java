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

import static org.assertj.core.api.Assertions.assertThat;
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
    void shouldSaveAndResolvePostIdByCorrelationId() {
        var correlationId = UUID.randomUUID();
        var postId = UUID.randomUUID();
        var entity = new PostRequestIdempotencyEntity(correlationId, postId, null);

        when(postRequestIdempotencyJpaRepository.findById(correlationId)).thenReturn(Optional.of(entity));

        repository.save(correlationId, postId);

        verify(postRequestIdempotencyJpaRepository).save(org.mockito.ArgumentMatchers.any(PostRequestIdempotencyEntity.class));
        assertThat(repository.findPostIdByCorrelationId(correlationId)).contains(postId);
    }
}
