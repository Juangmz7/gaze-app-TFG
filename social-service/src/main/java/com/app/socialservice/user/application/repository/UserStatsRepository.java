package com.app.socialservice.user.application.repository;

import java.util.UUID;

import com.app.socialservice.user.application.dto.UserStats;

public interface UserStatsRepository {

    /** Followers and following counted from the active follows, posts from the counter. */
    UserStats getStats(UUID userId);

    void incrementPostCount(UUID userId);

    void decrementPostCount(UUID userId);
}
