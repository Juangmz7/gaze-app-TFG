package com.app.socialservice.user.infrastructure.repository;

import java.util.UUID;

import com.app.socialservice.user.application.repository.UserStatsRepository;
import com.app.socialservice.user.infrastructure.entity.UserStatsEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

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

        var followerStats = getOrCreate(followerUserId);
        var followedStats = getOrCreate(followedUserId);

        followerStats.incrementFollowingCount();
        followedStats.incrementFollowerCount();

        jpaUserStatsRepository.save(followerStats);
        jpaUserStatsRepository.save(followedStats);
    }

    private UserStatsEntity getOrCreate(UUID userId) {
        return jpaUserStatsRepository.findById(userId)
                .orElseGet(() -> UserStatsEntity.builder()
                        .userId(userId)
                        .followerCount(0L)
                        .followingCount(0L)
                        .build());
    }
}
