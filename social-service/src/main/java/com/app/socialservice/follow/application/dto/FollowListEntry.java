package com.app.socialservice.follow.application.dto;

import java.time.Instant;
import java.util.UUID;

import com.app.socialservice.shared.domain.pagination.KeysetCursor;

/**
 * One row of a followers / following list as stored: the listed user and when the follow was
 * created (the list's sort key, with the user id).
 */
public record FollowListEntry(UUID userId, String username, String profilePic, Instant followedAt) {

    public KeysetCursor sortKey() {
        return new KeysetCursor(followedAt, userId);
    }
}
