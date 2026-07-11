package com.app.socialservice.block.application.repository;

import com.app.socialservice.block.domain.model.Block;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface BlockRepository {
    Optional<Block> findByUsers(UUID blockerUserId, UUID blockedUserId);
    Set<UUID> findBlockedUserIds(UUID userId);
    List<UUID> findBlockedUserIdsByBlockerId(UUID blockerUserId, int page, int limit);
    boolean existsByUsers(UUID blockerUserId, UUID blockedUserId);
    boolean insertIfAbsent(Block block);
    boolean deleteByUsers(UUID blockerUserId, UUID blockedUserId);
}
