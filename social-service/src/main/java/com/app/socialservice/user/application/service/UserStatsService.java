package com.app.socialservice.user.application.service;

import java.util.UUID;

import com.app.socialservice.user.application.repository.UserStatsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserStatsService {

    private final UserStatsRepository userStatsRepository;

    public void incrementFollowCounters(UUID followerUserId, UUID followedUserId) {
        validateDistinctUserIds(followerUserId, followedUserId);

        userStatsRepository.incrementFollowingCount(followerUserId);
        userStatsRepository.incrementFollowersCount(followedUserId);
    }

    public void decrementFollowCounters(UUID followerUserId, UUID followedUserId) {
        validateDistinctUserIds(followerUserId, followedUserId);

        userStatsRepository.decrementFollowingCount(followerUserId);
        userStatsRepository.decrementFollowersCount(followedUserId);
    }

    public void incrementPostCount(UUID userId) {
        validateUserId(userId, "userId");
        userStatsRepository.incrementPostCount(userId);
    }

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
