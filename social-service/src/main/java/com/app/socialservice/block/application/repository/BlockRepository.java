package com.app.socialservice.block.application.repository;

import com.app.socialservice.block.domain.model.Block;

import java.util.Optional;
import java.util.UUID;

public interface BlockRepository {
    Optional<Block> findByUsers(UUID blockerUserId, UUID blockedUserId);
    boolean existsByUsers(UUID blockerUserId, UUID blockedUserId);
    Block save(Block block);
}
