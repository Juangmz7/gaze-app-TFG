package com.app.socialservice.user.application.service;

import java.util.UUID;

import com.app.socialservice.user.application.dto.UserStats;
import com.app.socialservice.user.application.repository.UserStatsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserStatsService {

    private final UserStatsRepository userStatsRepository;
    private final UserCacheEvictor userCacheEvictor;

    public UserStats getStats(UUID userId) {
        validateUserId(userId);
        return userStatsRepository.getStats(userId);
    }

    /** Call in the transaction that records the event as processed, so it counts once. */
    public void incrementPostCount(UUID userId) {
        validateUserId(userId);
        userStatsRepository.incrementPostCount(userId);
        userCacheEvictor.evictProfiles(userId);
    }

    /** Call in the transaction that records the event as processed, so it counts once. */
    public void decrementPostCount(UUID userId) {
        validateUserId(userId);
        userStatsRepository.decrementPostCount(userId);
        userCacheEvictor.evictProfiles(userId);
    }

    private void validateUserId(UUID userId) {
        if (userId == null) {
            throw new IllegalArgumentException("userId must not be null");
        }
    }
}
