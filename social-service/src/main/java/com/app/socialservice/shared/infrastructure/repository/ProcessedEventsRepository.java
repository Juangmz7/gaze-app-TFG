package com.app.socialservice.shared.infrastructure.repository;

import com.app.socialservice.shared.infrastructure.entity.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ProcessedEventsRepository extends JpaRepository<ProcessedEvent, UUID> {
    boolean existsByCorrelationId(UUID correlationId);
}
