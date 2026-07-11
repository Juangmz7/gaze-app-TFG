package com.app.socialservice.user.application.service;

import java.util.UUID;

import com.app.socialservice.user.application.cache.CacheNames;
import com.app.socialservice.user.application.repository.UserStatsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserStatsService {

    private final UserStatsRepository userStatsRepository;

    public long getFollowersCount(UUID userId) {
        validateUserId(userId, "userId");
        return userStatsRepository.getFollowersCount(userId);
    }

    public long getFollowingCount(UUID userId) {
        validateUserId(userId, "userId");
        return userStatsRepository.getFollowingCount(userId);
    }

    public long getPostCount(UUID userId) {
        validateUserId(userId, "userId");
        return userStatsRepository.getPostCount(userId);
    }

    @Caching(evict = {
            @CacheEvict(
                    cacheNames = CacheNames.OWN_PROFILE,
                    key = CacheNames.OWN_PROFILE_KEY_BY_FOLLOWER_USER_ID
            ),
            @CacheEvict(
                    cacheNames = CacheNames.OWN_PROFILE,
                    key = CacheNames.OWN_PROFILE_KEY_BY_FOLLOWED_USER_ID
            ),
            @CacheEvict(cacheNames = CacheNames.PUBLIC_PROFILE, allEntries = true)
    })
    public void incrementFollowCounters(UUID followerUserId, UUID followedUserId) {
        validateDistinctUserIds(followerUserId, followedUserId);

        userStatsRepository.incrementFollowingCount(followerUserId);
        userStatsRepository.incrementFollowersCount(followedUserId);
    }

    @Caching(evict = {
            @CacheEvict(
                    cacheNames = CacheNames.OWN_PROFILE,
                    key = CacheNames.OWN_PROFILE_KEY_BY_FOLLOWER_USER_ID
            ),
            @CacheEvict(
                    cacheNames = CacheNames.OWN_PROFILE,
                    key = CacheNames.OWN_PROFILE_KEY_BY_FOLLOWED_USER_ID
            ),
            @CacheEvict(cacheNames = CacheNames.PUBLIC_PROFILE, allEntries = true)
    })
    public void decrementFollowCounters(UUID followerUserId, UUID followedUserId) {
        validateDistinctUserIds(followerUserId, followedUserId);

        userStatsRepository.decrementFollowingCount(followerUserId);
        userStatsRepository.decrementFollowersCount(followedUserId);
    }

    @Caching(evict = {
            @CacheEvict(
                    cacheNames = CacheNames.OWN_PROFILE,
                    key = CacheNames.OWN_PROFILE_KEY_BY_USER_ID
            ),
            @CacheEvict(cacheNames = CacheNames.PUBLIC_PROFILE, allEntries = true)
    })
    public void incrementPostCount(UUID userId) {
        validateUserId(userId, "userId");
        userStatsRepository.incrementPostCount(userId);
    }

    @Caching(evict = {
            @CacheEvict(
                    cacheNames = CacheNames.OWN_PROFILE,
                    key = CacheNames.OWN_PROFILE_KEY_BY_USER_ID
            ),
            @CacheEvict(cacheNames = CacheNames.PUBLIC_PROFILE, allEntries = true)
    })
    public void decrementPostCount(UUID userId) {
        validateUserId(userId, "userId");
        userStatsRepository.decrementPostCount(userId);
    }

    private void validateDistinctUserIds(UUID followerUserId, UUID followedUserId) {
        validateUserId(followerUserId, "followerUserId");
        validateUserId(followedUserId, "followedUserId");

        if (followerUserId.equals(followedUserId)) {
            throw new IllegalArgumentException("followerUserId must not equal followedUserId");
        }
    }

    private void validateUserId(UUID userId, String fieldName) {
        if (userId == null) {
            throw new IllegalArgumentException(fieldName + " must not be null");
        }
    }
}
