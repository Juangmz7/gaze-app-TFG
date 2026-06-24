package com.app.socialservice.shared.infrastructure.repository;

import com.app.socialservice.shared.infrastructure.entity.OutboxEvent;
import com.app.socialservice.shared.infrastructure.enums.EventStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {
    List<OutboxEvent> findOutboxEventByStatus(EventStatus status, Pageable pageable);

    List<OutboxEvent> findByEventTypeAndStatusOrderByCreatedAtAsc(
            String eventType,
            EventStatus status
    );

    @Modifying
    @Transactional
    @Query(value = "UPDATE outbox_event SET status = 'PROCESSED' WHERE id = :id AND status = 'PENDING'",
            nativeQuery = true)
    int markAsProcessedIfPending(@Param("id") UUID id);

    @Query(value = "SELECT * FROM outbox_event WHERE status = 'PENDING' AND created_at < NOW() - INTERVAL '30 seconds' "
            + "ORDER BY created_at LIMIT :limit FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    List<OutboxEvent> findPendingForProcessing(@Param("limit") int limit);
}
