package com.app.postcommandservice.shared.infrastructure.entity;

import com.app.postcommandservice.shared.infrastructure.enums.EventStatus;
import com.app.postcommandservice.shared.infrastructure.outbox.OutboxDestinationResolver;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Builder
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@Entity
@Table(name = "outbox_event")
@EntityListeners(OutboxDestinationResolver.class)
public class OutboxEvent {
    @Id
    @Column(nullable = false)
    private UUID id;

    @Column(nullable = false)
    private UUID correlationId;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String payload;

    @Column(nullable = false)
    private String eventType;

    // Filled by OutboxDestinationResolver at insert time
    @Column(nullable = false)
    private String exchange;

    @Column(nullable = false)
    private String routingKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EventStatus status;

    @Builder.Default
    @Column(nullable = false)
    private int attempts = 0;

    @Column(columnDefinition = "TEXT")
    private String lastError;

    private Instant lockedAt;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant processedAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}
