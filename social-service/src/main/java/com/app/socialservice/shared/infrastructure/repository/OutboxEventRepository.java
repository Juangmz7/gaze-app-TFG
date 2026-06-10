package com.app.socialservice.shared.infrastructure.repository;

import com.app.socialservice.shared.infrastructure.entity.OutboxEvent;
import com.app.socialservice.shared.infrastructure.enums.EventStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {
    List<OutboxEvent> findOutboxEventByStatus(EventStatus status, Pageable pageable);
}
