package com.app.socialservice.user.infrastructure.repository;

import java.util.UUID;

import com.app.socialservice.user.application.dto.UserStats;
import com.app.socialservice.user.application.repository.UserStatsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * PostgreSQL counters. Followers and following are counted from {@code follows}, the
 * table follow, unfollow and block change in their own transaction, so they cannot
 * drift. The post count is the one counter: its events come from post-command-service,
 * and the caller changes it in the same transaction as the processed event.
 */
@Repository
@RequiredArgsConstructor
public class UserStatsRepositoryImpl implements UserStatsRepository {

    private final JpaUserStatsRepository jpaUserStatsRepository;

    @Override
    public UserStats getStats(UUID userId) {
        validateUserId(userId);

        var stats = jpaUserStatsRepository.findStats(userId);
        if (stats == null) {
            return UserStats.EMPTY;
        }
        return new UserStats(stats.getFollowersCount(), stats.getFollowingCount(), stats.getPostCount());
    }

    @Override
    public void incrementPostCount(UUID userId) {
        validateUserId(userId);
        jpaUserStatsRepository.incrementPostCount(userId);
    }

    @Override
    public void decrementPostCount(UUID userId) {
        validateUserId(userId);
        jpaUserStatsRepository.decrementPostCount(userId);
    }

    private void validateUserId(UUID userId) {
        if (userId == null) {
            throw new IllegalArgumentException("userId must not be null");
        }
    }
}
