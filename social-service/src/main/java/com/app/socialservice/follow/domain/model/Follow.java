package com.app.socialservice.follow.domain.model;

import java.time.Instant;

import com.app.socialservice.user.domain.model.valueobj.UserId;

public class Follow {

    private final UserId followerId;
    private final UserId followedId;
    private final Instant createdAt;

    public Follow(UserId followerId, UserId followedId, Instant createdAt) {
        if (followerId == null) {
            throw new IllegalArgumentException("followerId must not be null");
        }
        if (followedId == null) {
            throw new IllegalArgumentException("followedId must not be null");
        }
        if (createdAt == null) {
            throw new IllegalArgumentException("createdAt must not be null");
        }

        this.followerId = followerId;
        this.followedId = followedId;
        this.createdAt = createdAt;
    }

    public UserId getFollowerId() {
        return followerId;
    }

    public UserId getFollowedId() {
        return followedId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
