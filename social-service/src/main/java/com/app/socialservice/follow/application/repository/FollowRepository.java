package com.app.socialservice.follow.application.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.app.socialservice.follow.application.dto.FollowListEntry;
import com.app.socialservice.follow.domain.model.Follow;
import com.app.socialservice.shared.domain.pagination.KeysetCursor;

public interface FollowRepository {
    Optional<Follow> findActiveByUsers(UUID followerUserId, UUID followedUserId);
    Optional<Follow> findRemovedByUsers(UUID followerUserId, UUID followedUserId);
    boolean existsBlockedByUsers(UUID followerUserId, UUID followedUserId);
    boolean insertIfAbsent(Follow follow);
    boolean reactivate(UUID followerUserId, UUID followedUserId);
    boolean markAsRemoved(UUID followerUserId, UUID followedUserId);
    boolean markBidirectionalRelationshipsAsBlocked(UUID firstUserId, UUID secondUserId);
    boolean markBidirectionalRelationshipsAsRemoved(UUID firstUserId, UUID secondUserId);

    /**
     * Active followers of followedUserId, newest follow first (then user id DESC), only ACCEPTED
     * users with no block relation with viewerUserId. after is null for the first page.
     */
    List<FollowListEntry> findFollowersPage(UUID followedUserId, UUID viewerUserId, KeysetCursor after, int fetchSize);

    /**
     * The users followerUserId actively follows, with the same order and filters as findFollowersPage.
     */
    List<FollowListEntry> findFollowingPage(UUID followerUserId, UUID viewerUserId, KeysetCursor after, int fetchSize);

    Set<UUID> findActivelyFollowedAmong(UUID followerUserId, Collection<UUID> candidateUserIds);
}
