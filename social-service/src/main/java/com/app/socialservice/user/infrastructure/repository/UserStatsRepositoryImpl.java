package com.app.socialservice.user.infrastructure.repository;

import java.util.UUID;

import com.app.socialservice.user.application.repository.UserStatsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.data.redis.core.StringRedisTemplate;

@Repository
@RequiredArgsConstructor
public class UserStatsRepositoryImpl implements UserStatsRepository {

    private static final String FOLLOWERS_COUNTER = "followers";
    private static final String FOLLOWING_COUNTER = "following";
    private static final String POST_COUNT_COUNTER = "postCount";
    private static final String KEY_PATTERN = "user:stats:%s:%s";

    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public long getFollowersCount(UUID userId) {
        return getCounterValue(userId, FOLLOWERS_COUNTER);
    }

    @Override
    public long getFollowingCount(UUID userId) {
        return getCounterValue(userId, FOLLOWING_COUNTER);
    }

    @Override
    public long getPostCount(UUID userId) {
        return getCounterValue(userId, POST_COUNT_COUNTER);
    }

    @Override
    public void incrementFollowersCount(UUID userId) {
        incrementCounter(userId, FOLLOWERS_COUNTER);
    }

    @Override
    public void decrementFollowersCount(UUID userId) {
        decrementCounter(userId, FOLLOWERS_COUNTER);
    }

    @Override
    public void incrementFollowingCount(UUID userId) {
        incrementCounter(userId, FOLLOWING_COUNTER);
    }

    @Override
    public void decrementFollowingCount(UUID userId) {
        decrementCounter(userId, FOLLOWING_COUNTER);
    }

    @Override
    public void incrementPostCount(UUID userId) {
        incrementCounter(userId, POST_COUNT_COUNTER);
    }

    @Override
    public void decrementPostCount(UUID userId) {
        decrementCounter(userId, POST_COUNT_COUNTER);
    }

    private void incrementCounter(UUID userId, String counterName) {
        validateUserId(userId);
        stringRedisTemplate.opsForValue().increment(buildKey(userId, counterName));
    }

    private void decrementCounter(UUID userId, String counterName) {
        validateUserId(userId);
        stringRedisTemplate.opsForValue().decrement(buildKey(userId, counterName));
    }

    private long getCounterValue(UUID userId, String counterName) {
        validateUserId(userId);

        var value = stringRedisTemplate.opsForValue().get(buildKey(userId, counterName));
        if (value == null) {
            return 0L;
        }

        return Long.parseLong(value);
    }

    private void validateUserId(UUID userId) {
        if (userId == null) {
            throw new IllegalArgumentException("userId must not be null");
        }
    }

    private String buildKey(UUID userId, String counterName) {
        return String.format(KEY_PATTERN, userId, counterName);
    }
}
