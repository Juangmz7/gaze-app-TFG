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
public class ProcessedEvents {
    @Id
    private UUID id;

    @Column(nullable = false)
    private Instant processedAt;

    public ProcessedEvents(UUID correlationId) {
        this.id = correlationId;
    }

    @PrePersist
    protected void onCreate() {
        this.processedAt = Instant.now();
    }
}
