package com.app.socialservice.follow.testutil;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.app.socialservice.follow.domain.model.Follow;
import com.app.socialservice.follow.infrastructure.entity.FollowEntity;
import com.app.socialservice.follow.infrastructure.entity.FollowEntityId;
import com.app.socialservice.follow.infrastructure.enums.FollowStatus;
import com.app.socialservice.user.domain.model.valueobj.UserId;

public final class FollowMother {

    private FollowMother() {
    }

    public static Follow active(UUID followerId, UUID followedId) {
        return active(followerId, followedId, Instant.now());
    }

    public static Follow active(UUID followerId, UUID followedId, Instant createdAt) {
        Objects.requireNonNull(followerId, "followerId must not be null");
        Objects.requireNonNull(followedId, "followedId must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");

        return new Follow(new UserId(followerId), new UserId(followedId), createdAt);
    }

    public static Follow removed(UUID followerId, UUID followedId, Instant createdAt) {
        Objects.requireNonNull(followerId, "followerId must not be null");
        Objects.requireNonNull(followedId, "followedId must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");

        return new Follow(new UserId(followerId), new UserId(followedId), createdAt);
    }

    public static FollowEntity activeEntity(UUID followerId, UUID followedId, Instant createdAt, Instant updatedAt) {
        return entity(followerId, followedId, FollowStatus.ACTIVE, createdAt, updatedAt);
    }

    public static FollowEntity removedEntity(UUID followerId, UUID followedId, Instant createdAt, Instant updatedAt) {
        return entity(followerId, followedId, FollowStatus.REMOVED, createdAt, updatedAt);
    }

    public static FollowEntity blockedEntity(UUID followerId, UUID followedId, Instant createdAt, Instant updatedAt) {
        return entity(followerId, followedId, FollowStatus.BLOCKED, createdAt, updatedAt);
    }

    public static FollowEntity entity(
            UUID followerId,
            UUID followedId,
            FollowStatus status,
            Instant createdAt,
            Instant updatedAt
    ) {
        Objects.requireNonNull(followerId, "followerId must not be null");
        Objects.requireNonNull(followedId, "followedId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");

        return new FollowEntity(
                new FollowEntityId(followerId, followedId),
                status,
                createdAt,
                updatedAt
        );
    }
}
