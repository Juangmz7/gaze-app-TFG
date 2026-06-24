package com.app.socialservice.follow.application.repository;

import java.util.UUID;

public interface FollowGraphRepository {
    void createFollowRelationship(UUID followerUserId, UUID followedUserId);
    void deleteFollowRelationship(UUID followerUserId, UUID followedUserId);
    void deleteBidirectionalFollowRelationship(UUID firstUserId, UUID secondUserId);
}
