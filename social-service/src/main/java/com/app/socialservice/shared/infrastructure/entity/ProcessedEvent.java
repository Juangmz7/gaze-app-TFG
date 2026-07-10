package com.app.socialservice.shared.infrastructure.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@NoArgsConstructor
@Getter
@Setter
@Entity
@IdClass(ProcessedEventId.class)
@Table(
        name = "processed_events",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_processed_events_correlation_id_target_database",
                        columnNames = {"correlation_id", "target_database"}
                )
        },
        indexes = {
                @Index(
                        name = "idx_processed_events_correlation_id_target_database",
                        columnList = "correlation_id, target_database"
                )
        }
)
public class ProcessedEvent {
    @Id
    private UUID id;

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "target_database", nullable = false, length = 32)
    private TargetDatabase targetDatabase;

    @Column(name = "correlation_id", nullable = false)
    private UUID correlationId;

    @Column(nullable = false)
    private String eventType;

    @Column(nullable = false)
    private Instant processedAt;

    public ProcessedEvent(UUID eventId, UUID correlationId, String eventType, TargetDatabase targetDatabase) {
        this.id = eventId;
        this.targetDatabase = targetDatabase;
        this.correlationId = correlationId;
        this.eventType = eventType;
    }

    @PrePersist
    protected void onCreate() {
        this.processedAt = Instant.now();
    }
}
