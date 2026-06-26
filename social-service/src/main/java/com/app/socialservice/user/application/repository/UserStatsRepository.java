package com.app.socialservice.user.application.repository;

import java.util.UUID;

public interface UserStatsRepository {
    void incrementFollowersCount(UUID userId);

    void decrementFollowersCount(UUID userId);

    void incrementFollowingCount(UUID userId);

    void decrementFollowingCount(UUID userId);

    void incrementPostCount(UUID userId);

    void decrementPostCount(UUID userId);
}
