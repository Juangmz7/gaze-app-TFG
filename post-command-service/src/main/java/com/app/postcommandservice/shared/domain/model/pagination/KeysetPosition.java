package com.app.postcommandservice.shared.domain.model.pagination;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Sort key of the last row of a keyset page ordered by {@code (createdAt DESC, id DESC)}.
 * The next page starts strictly after it.
 */
public record KeysetPosition(Instant createdAt, UUID id) {

    public KeysetPosition {
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(id, "id must not be null");
    }
}
