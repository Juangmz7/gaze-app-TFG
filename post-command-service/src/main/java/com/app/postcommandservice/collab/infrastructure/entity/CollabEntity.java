package com.app.postcommandservice.collab.infrastructure.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import com.app.postcommandservice.collab.domain.model.valueobj.ColabStatus;

@Getter
@Setter
@Entity
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "collabs")
public class CollabEntity {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "collab_status", nullable = false)
    private ColabStatus collabStatus;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // Optional, already trimmed and length-checked by the domain (task 61)
    @Column(name = "join_instructions", columnDefinition = "TEXT")
    private String joinInstructions;

    public CollabEntity(UUID id, String title, UUID createdBy, ColabStatus collabStatus, Instant createdAt) {
        this(id, title, createdBy, collabStatus, createdAt, null);
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}
