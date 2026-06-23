package com.app.socialservice.block.domain.model;

import com.app.socialservice.user.domain.model.valueobj.UserId;

import java.time.Instant;

public class Block {

    private final UserId blockerId;
    private final UserId blockedId;
    private final Instant createdAt;

    public Block(UserId blockerId, UserId blockedId, Instant createdAt) {
        if (blockerId == null) {
            throw new IllegalArgumentException("blockerId must not be null");
        }
        if (blockedId == null) {
            throw new IllegalArgumentException("blockedId must not be null");
        }
        if (createdAt == null) {
            throw new IllegalArgumentException("createdAt must not be null");
        }
        this.blockerId = blockerId;
        this.blockedId = blockedId;
        this.createdAt = createdAt;
    }

    public UserId getBlockerId() {
        return blockerId;
    }

    public UserId getBlockedId() {
        return blockedId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
