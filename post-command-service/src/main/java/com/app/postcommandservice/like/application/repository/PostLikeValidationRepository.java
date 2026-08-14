package com.app.postcommandservice.like.application.repository;

import java.util.Optional;
import java.util.UUID;

public interface PostLikeValidationRepository {

    Optional<ActivePost> findActivePost(UUID postId);

    boolean existsBlockRelationship(UUID likerUserId, UUID postOwnerId);

    record ActivePost(UUID postId, UUID ownerUserId) {
    }
}
