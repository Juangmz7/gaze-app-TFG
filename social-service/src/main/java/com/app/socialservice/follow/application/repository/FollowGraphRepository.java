package com.app.socialservice.follow.application.repository;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.app.socialservice.follow.application.dto.RecommendedFollowCandidate;

public interface FollowGraphRepository {
    void createFollowRelationship(UUID followerUserId, UUID followedUserId);
    void deleteFollowRelationship(UUID followerUserId, UUID followedUserId);
    void deleteBidirectionalFollowRelationship(UUID firstUserId, UUID secondUserId);
    List<RecommendedFollowCandidate> findRecommendedUsers(UUID requesterUserId, Set<UUID> blockedUserIds, int limit);
}
