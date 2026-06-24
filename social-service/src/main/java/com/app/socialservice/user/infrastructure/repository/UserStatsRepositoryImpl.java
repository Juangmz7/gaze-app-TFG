package com.app.socialservice.user.infrastructure.repository;

import java.util.UUID;

import com.app.socialservice.user.application.repository.UserStatsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

@Slf4j
@Repository
@RequiredArgsConstructor
public class UserStatsRepositoryImpl implements UserStatsRepository {

    private final JpaUserStatsRepository jpaUserStatsRepository;

    @Override
    public void incrementFollowCounters(UUID followerUserId, UUID followedUserId) {
        if (followerUserId == null) {
            throw new IllegalArgumentException("followerUserId must not be null");
        }
        if (followedUserId == null) {
            throw new IllegalArgumentException("followedUserId must not be null");
        }

        jpaUserStatsRepository.ensureExists(followerUserId);
        jpaUserStatsRepository.ensureExists(followedUserId);

        int followingRows = jpaUserStatsRepository.incrementFollowingCount(followerUserId);
        if (followingRows == 0) {
            log.warn("Failed to increment following_count for user {}: no row affected", followerUserId);
        }

        int followerRows = jpaUserStatsRepository.incrementFollowerCount(followedUserId);
        if (followerRows == 0) {
            log.warn("Failed to increment follower_count for user {}: no row affected", followedUserId);
        }
    }

    @Override
    public void decrementFollowCounters(UUID followerUserId, UUID followedUserId) {
        if (followerUserId == null) {
            throw new IllegalArgumentException("followerUserId must not be null");
        }
        if (followedUserId == null) {
            throw new IllegalArgumentException("followedUserId must not be null");
        }

        int followingRows = jpaUserStatsRepository.decrementFollowingCount(followerUserId);
        if (followingRows == 0) {
            log.warn("Failed to decrement following_count for user {}: no row affected or already zero",
                    followerUserId);
        }

        int followerRows = jpaUserStatsRepository.decrementFollowerCount(followedUserId);
        if (followerRows == 0) {
            log.warn("Failed to decrement follower_count for user {}: no row affected or already zero",
                    followedUserId);
        }
    }
}
