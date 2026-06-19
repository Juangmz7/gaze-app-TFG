package com.app.socialservice.shared.infrastructure.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@NoArgsConstructor
@Getter
@Setter
@Entity
@Table(name = "processed_events", indexes = {
        @Index(name = "idx_processed_events_correlation_id", columnList = "correlationId")
})
public class ProcessedEvent {
    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID correlationId;

    @Column(nullable = false)
    private String eventType;

    @Column(nullable = false)
    private Instant processedAt;

    public ProcessedEvent(UUID eventId, UUID correlationId, String eventType) {
        this.id = eventId;
        this.correlationId = correlationId;
        this.eventType = eventType;
    }

    @PrePersist
    protected void onCreate() {
        this.processedAt = Instant.now();
    }
}
