package com.app.postcommandservice.post.infrastructure.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
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
@Table(
        name = "post_request_idempotency",
        indexes = {
                @Index(name = "idx_post_request_idempotency_post_id", columnList = "post_id")
        }
)
public class PostRequestIdempotencyEntity {

    @Id
    @Column(name = "correlation_id", nullable = false, updatable = false)
    private UUID correlationId;

    @Column(name = "post_id", nullable = false, updatable = false, unique = true)
    private UUID postId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
