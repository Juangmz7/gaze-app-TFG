package com.app.postcommandservice.share.application.repository;

import java.util.Optional;
import java.util.UUID;

import com.app.postcommandservice.post.domain.model.valueobj.PostStatus;

public interface PostShareValidationRepository {

    Optional<ShareablePost> findPost(UUID postId);

    boolean existsBlockRelationship(UUID sharerUserId, UUID postOwnerId);

    record ShareablePost(
            UUID postId,
            UUID ownerUserId,
            PostStatus status
    ) {
    }
}
