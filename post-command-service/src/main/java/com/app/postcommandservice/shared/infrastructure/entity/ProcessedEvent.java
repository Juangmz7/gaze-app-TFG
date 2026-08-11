package com.app.postcommandservice.shared.infrastructure.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@NoArgsConstructor
@Getter
@Setter
@Entity
@Table(
        name = "processed_events",
        indexes = {
                @Index(
                        name = "idx_processed_events_correlation_id",
                        columnList = "correlation_id"
                )
        }
)
public class ProcessedEvent {
    @Id
    private UUID id;

    @Column(name = "correlation_id", nullable = false)
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
