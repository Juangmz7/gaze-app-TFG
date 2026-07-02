package com.app.socialservice.block.infrastructure.repository;

import com.app.socialservice.block.application.repository.BlockRepository;
import com.app.socialservice.block.domain.model.Block;
import com.app.socialservice.block.infrastructure.entity.BlockEntity;
import com.app.socialservice.block.infrastructure.entity.BlockEntityId;
import com.app.socialservice.user.domain.model.valueobj.UserId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class BlockRepositoryImpl implements BlockRepository {

    private final JpaBlockRepository jpaBlockRepository;

    @Override
    public Optional<Block> findByUsers(UUID blockerUserId, UUID blockedUserId) {
        return jpaBlockRepository.findById(new BlockEntityId(blockerUserId, blockedUserId))
                .map(this::toDomain);
    }

    @Override
    public Set<UUID> findBlockedUserIds(UUID userId) {
        if (userId == null) {
            throw new IllegalArgumentException("userId must not be null");
        }

        return Set.copyOf(jpaBlockRepository.findBlockedUserIds(userId));
    }

    @Override
    public boolean existsByUsers(UUID blockerUserId, UUID blockedUserId) {
        validateUserIds(blockerUserId, blockedUserId);
        return jpaBlockRepository.existsById(new BlockEntityId(blockerUserId, blockedUserId));
    }

    @Override
    public boolean insertIfAbsent(Block block) {
        int rows = jpaBlockRepository.insertIfAbsent(
                block.getBlockerId().value(),
                block.getBlockedId().value(),
                block.getCreatedAt()
        );
        return rows > 0;
    }

    @Override
    public boolean deleteByUsers(UUID blockerUserId, UUID blockedUserId) {
        validateUserIds(blockerUserId, blockedUserId);
        int rows = jpaBlockRepository.deleteByUsers(blockerUserId, blockedUserId);
        return rows > 0;
    }

    private Block toDomain(BlockEntity entity) {
        return new Block(
                new UserId(entity.getId().getBlockerId()),
                new UserId(entity.getId().getBlockedId()),
                entity.getCreatedAt()
        );
    }

    private void validateUserIds(UUID blockerUserId, UUID blockedUserId) {
        if (blockerUserId == null) {
            throw new IllegalArgumentException("blockerUserId must not be null");
        }
        if (blockedUserId == null) {
            throw new IllegalArgumentException("blockedUserId must not be null");
        }
    }
}
