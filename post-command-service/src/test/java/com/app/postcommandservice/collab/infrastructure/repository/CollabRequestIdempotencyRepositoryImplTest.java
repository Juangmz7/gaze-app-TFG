package com.app.postcommandservice.collab.infrastructure.repository;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.app.postcommandservice.collab.infrastructure.entity.CollabRequestIdempotencyEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CollabRequestIdempotencyRepositoryImplTest {

    @Mock
    private CollabRequestIdempotencyJpaRepository collabRequestIdempotencyJpaRepository;

    @Mock
    private EntityManager entityManager;

    @Mock
    private Query query;

    private CollabRequestIdempotencyRepositoryImpl repository;

    @BeforeEach
    void setUp() {
        repository = new CollabRequestIdempotencyRepositoryImpl(collabRequestIdempotencyJpaRepository, entityManager);
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
    void shouldSaveAndResolveEntityIdByCorrelationId() {
        var correlationId = UUID.randomUUID();
        var entityId = UUID.randomUUID();
        var entity = new CollabRequestIdempotencyEntity(correlationId, entityId, null);

        when(collabRequestIdempotencyJpaRepository.findById(correlationId)).thenReturn(Optional.of(entity));

        repository.save(correlationId, entityId);

        verify(collabRequestIdempotencyJpaRepository)
                .save(org.mockito.ArgumentMatchers.any(CollabRequestIdempotencyEntity.class));
        assertThat(repository.findEntityIdByCorrelationId(correlationId)).contains(entityId);
    }
}
