package com.app.postcommandservice.post.infrastructure.entity;

import java.io.Serializable;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Composite key scoping a post-creation request-idempotency record by the requesting
 * user, not just the client-supplied correlation id. A correlation id is only client
 * generated and must never let one user replay/discover another user's post by reusing
 * the same correlation id.
 */
@Embeddable
@Getter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class PostRequestIdempotencyId implements Serializable {

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "correlation_id", nullable = false, updatable = false)
    private UUID correlationId;
}
