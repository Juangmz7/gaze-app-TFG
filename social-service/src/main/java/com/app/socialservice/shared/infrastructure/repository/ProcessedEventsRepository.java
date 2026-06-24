package com.app.socialservice.shared.infrastructure.repository;

import com.app.socialservice.shared.infrastructure.entity.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

public interface ProcessedEventsRepository extends JpaRepository<ProcessedEvent, UUID> {
    boolean existsByCorrelationId(UUID correlationId);

    @Modifying
    @Transactional
    @Query(value = "INSERT INTO processed_events (id, correlation_id, event_type, processed_at) "
            + "VALUES (:id, :correlationId, :eventType, NOW()) "
            + "ON CONFLICT (id) DO NOTHING",
            nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id,
                       @Param("correlationId") UUID correlationId,
                       @Param("eventType") String eventType);
}
