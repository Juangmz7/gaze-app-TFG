package com.app.socialservice.block.application.dto;

import com.app.socialservice.block.domain.model.Block;

import java.util.UUID;

public record BlockPersistenceResult(Block block, boolean created, UUID outboxEventId) {

    public BlockPersistenceResult {
        if (block == null) {
            throw new IllegalArgumentException("block must not be null");
        }
        if (created && outboxEventId == null) {
            throw new IllegalArgumentException("outboxEventId must not be null when block is newly created");
        }
    }
}
