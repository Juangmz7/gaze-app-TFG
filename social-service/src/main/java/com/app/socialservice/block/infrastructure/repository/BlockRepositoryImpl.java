package com.app.socialservice.block.infrastructure.repository;

import com.app.socialservice.block.application.repository.BlockRepository;
import com.app.socialservice.block.domain.model.Block;
import com.app.socialservice.block.infrastructure.entity.BlockEntity;
import com.app.socialservice.block.infrastructure.entity.BlockEntityId;
import com.app.socialservice.user.domain.model.valueobj.UserId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Optional;
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
    public boolean existsByUsers(UUID blockerUserId, UUID blockedUserId) {
        validateUserIds(blockerUserId, blockedUserId);
        return jpaBlockRepository.existsById(new BlockEntityId(blockerUserId, blockedUserId));
    }

    @Override
    public Block save(Block block) {
        if (block == null) {
            throw new IllegalArgumentException("block must not be null");
        }

        var savedEntity = jpaBlockRepository.save(toEntity(block));
        return toDomain(savedEntity);
    }

    private Block toDomain(BlockEntity entity) {
        return new Block(
                new UserId(entity.getId().getBlockerId()),
                new UserId(entity.getId().getBlockedId()),
                entity.getCreatedAt()
        );
    }

    private BlockEntity toEntity(Block block) {
        return new BlockEntity(
                new BlockEntityId(block.getBlockerId().value(), block.getBlockedId().value()),
                block.getCreatedAt()
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
