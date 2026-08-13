package com.app.postcommandservice.post.infrastructure.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Entity
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "blocks")
public class BlockReadModelEntity {

    @EmbeddedId
    private BlockReadModelId id;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
