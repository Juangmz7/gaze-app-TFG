package com.app.socialservice.follow.application.repository;

import java.util.UUID;

public interface FollowGraphRepository {
    void deleteBidirectionalFollowRelationship(UUID firstUserId, UUID secondUserId);
}
