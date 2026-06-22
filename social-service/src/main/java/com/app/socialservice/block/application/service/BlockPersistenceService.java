package com.app.socialservice.block.application.service;

import com.app.socialservice.block.application.dto.BlockPersistenceResult;
import com.app.socialservice.block.application.repository.BlockRepository;
import com.app.socialservice.block.domain.model.Block;
import com.app.socialservice.follow.application.repository.FollowRepository;
import com.app.socialservice.user.domain.model.valueobj.UserId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class BlockPersistenceService {

    private final BlockRepository blockRepository;
    private final FollowRepository followRepository;
    private final BlockEventService blockEventService;

    @Transactional
    public BlockPersistenceResult createBlockAndUpdateFollows(UUID blockerUserId, UUID blockedUserId) {
        if (blockerUserId == null) {
            throw new IllegalArgumentException("blockerUserId must not be null");
        }
        if (blockedUserId == null) {
            throw new IllegalArgumentException("blockedUserId must not be null");
        }

        var existingBlock = blockRepository.findByUsers(blockerUserId, blockedUserId);
        if (existingBlock.isPresent()) {
            followRepository.markBidirectionalRelationshipsAsBlocked(blockerUserId, blockedUserId);
            var waitingOutboxEventId = blockEventService.findWaitingBlockEventId(existingBlock.get());
            log.info("Block already exists for blocker {} and blocked {}", blockerUserId, blockedUserId);
            return new BlockPersistenceResult(existingBlock.get(), false, waitingOutboxEventId);
        }

        var block = new Block(
                new UserId(blockerUserId),
                new UserId(blockedUserId),
                Instant.now()
        );
        var savedBlock = blockRepository.save(block);
        followRepository.markBidirectionalRelationshipsAsBlocked(blockerUserId, blockedUserId);
        var outboxEventId = blockEventService.storePendingBlockEvent(savedBlock);

        log.info("Block created for blocker {} and blocked {}", blockerUserId, blockedUserId);
        return new BlockPersistenceResult(savedBlock, true, outboxEventId);
    }
}
