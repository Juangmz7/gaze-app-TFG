package com.app.socialservice.shared.infrastructure.repository;

import com.app.socialservice.shared.infrastructure.entity.ProcessedEvent;
import com.app.socialservice.shared.infrastructure.entity.ProcessedEventId;
import com.app.socialservice.shared.infrastructure.entity.TargetDatabase;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

public interface ProcessedEventsRepository extends JpaRepository<ProcessedEvent, ProcessedEventId> {
    boolean existsByIdAndTargetDatabase(UUID id, TargetDatabase targetDatabase);

    boolean existsByCorrelationIdAndTargetDatabase(UUID correlationId, TargetDatabase targetDatabase);

    java.util.Optional<ProcessedEvent> findByIdAndTargetDatabase(UUID id, TargetDatabase targetDatabase);

    java.util.Optional<ProcessedEvent> findByCorrelationIdAndTargetDatabase(UUID correlationId,
                                                                            TargetDatabase targetDatabase);

    @Modifying
    @Transactional
    @Query(value = "INSERT INTO processed_events (id, target_database, correlation_id, event_type, processed_at) "
            + "VALUES (:id, :targetDatabase, :correlationId, :eventType, NOW()) "
            + "ON CONFLICT DO NOTHING",
            nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id,
                       @Param("targetDatabase") String targetDatabase,
                       @Param("correlationId") UUID correlationId,
                       @Param("eventType") String eventType);
}
