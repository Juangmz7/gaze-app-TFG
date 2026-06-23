package com.app.socialservice.follow.application.repository;

import java.util.Optional;
import java.util.UUID;

import com.app.socialservice.follow.domain.model.Follow;

public interface FollowRepository {
    Optional<Follow> findActiveByUsers(UUID followerUserId, UUID followedUserId);
    Optional<Follow> findRemovedByUsers(UUID followerUserId, UUID followedUserId);
    boolean existsBlockedByUsers(UUID followerUserId, UUID followedUserId);
    Follow save(Follow follow);
    Follow reactivate(UUID followerUserId, UUID followedUserId);
    void markBidirectionalRelationshipsAsBlocked(UUID firstUserId, UUID secondUserId);
}
