package com.app.postcommandservice.comment.application.repository;

import java.util.Optional;
import java.util.UUID;

public interface CommentRequestIdempotencyRepository {

    void acquireCorrelationLock(UUID correlationId);

    Optional<UUID> findCommentIdByCorrelationId(UUID correlationId);

    void save(UUID correlationId, UUID commentId);
}
