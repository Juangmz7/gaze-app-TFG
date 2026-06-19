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
@Table(name = "processed_events")
public class ProcessedEvent {
    @Id
    private UUID id;
    private UUID correlationId;
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
