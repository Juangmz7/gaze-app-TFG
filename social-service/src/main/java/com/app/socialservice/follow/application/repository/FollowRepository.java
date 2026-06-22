package com.app.socialservice.follow.application.repository;

import java.util.UUID;

public interface FollowRepository {
    void markBidirectionalRelationshipsAsBlocked(UUID firstUserId, UUID secondUserId);
}
