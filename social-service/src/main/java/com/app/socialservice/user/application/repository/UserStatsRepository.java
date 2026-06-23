package com.app.socialservice.user.application.repository;

import java.util.UUID;

public interface UserStatsRepository {
    void incrementFollowCounters(UUID followerUserId, UUID followedUserId);
}
