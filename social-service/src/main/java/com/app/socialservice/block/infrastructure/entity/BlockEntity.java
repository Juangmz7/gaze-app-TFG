package com.app.socialservice.block.infrastructure.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "blocks")
@NoArgsConstructor
@AllArgsConstructor
public class BlockEntity {

    @EmbeddedId
    private BlockEntityId id;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
