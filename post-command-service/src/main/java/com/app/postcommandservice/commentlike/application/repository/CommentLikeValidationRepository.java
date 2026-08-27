package com.app.postcommandservice.commentlike.application.repository;

import java.util.Optional;
import java.util.UUID;

public interface CommentLikeValidationRepository {

    Optional<ActiveComment> findActiveComment(UUID postId, UUID commentId);

    boolean existsBlockRelationship(UUID likerUserId, UUID commentOwnerId);

    record ActiveComment(UUID postId, UUID commentId, UUID ownerUserId) {
    }
}
