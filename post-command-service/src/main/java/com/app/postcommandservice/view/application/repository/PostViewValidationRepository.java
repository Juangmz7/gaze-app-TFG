package com.app.postcommandservice.view.application.repository;

import java.util.Optional;
import java.util.UUID;

public interface PostViewValidationRepository {

    Optional<ActivePost> findActivePost(UUID postId);

    boolean existsBlockRelationship(UUID viewerUserId, UUID creatorUserId);

    record ActivePost(UUID postId, UUID ownerUserId) {
    }
}
