package com.app.socialservice.shared.infrastructure.repository;

import com.app.socialservice.shared.infrastructure.entity.OutboxEvent;
import com.app.socialservice.shared.infrastructure.enums.EventStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {
    List<OutboxEvent> findOutboxEventByStatus(EventStatus status, Pageable pageable);

    List<OutboxEvent> findByEventTypeAndStatusOrderByCreatedAtAsc(
            String eventType,
            EventStatus status
    );
}
