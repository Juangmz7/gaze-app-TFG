package com.app.socialservice.block.application.service;

import com.app.socialservice.block.application.dto.BlockPersistenceResult;
import com.app.socialservice.block.application.repository.BlockRepository;
import com.app.socialservice.block.domain.model.Block;
import com.app.socialservice.follow.application.repository.FollowRepository;
import com.app.socialservice.user.domain.model.valueobj.UserId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BlockPersistenceServiceTest {

    @Mock
    private BlockRepository blockRepository;

    @Mock
    private FollowRepository followRepository;

    @Mock
    private BlockEventService blockEventService;

    @InjectMocks
    private BlockPersistenceService blockPersistenceService;

    @Test
    void shouldCreateBlockUpdateFollowsAndStoreWaitingOutboxAtomically() {
        var blockerId = UUID.randomUUID();
        var blockedId = UUID.randomUUID();
        var outboxEventId = UUID.randomUUID();
        var savedBlock = new Block(new UserId(blockerId), new UserId(blockedId), Instant.now());

        when(blockRepository.findByUsers(blockerId, blockedId)).thenReturn(Optional.empty());
        when(blockRepository.save(org.mockito.ArgumentMatchers.any(Block.class))).thenReturn(savedBlock);
        when(blockEventService.storePendingBlockEvent(savedBlock)).thenReturn(outboxEventId);

        BlockPersistenceResult result = blockPersistenceService.createBlockAndUpdateFollows(blockerId, blockedId);

        assertThat(result.created()).isTrue();
        assertThat(result.block()).isEqualTo(savedBlock);
        assertThat(result.outboxEventId()).isEqualTo(outboxEventId);
        verify(blockRepository).save(org.mockito.ArgumentMatchers.any(Block.class));
        verify(followRepository).markBidirectionalRelationshipsAsBlocked(blockerId, blockedId);
        verify(blockEventService).storePendingBlockEvent(savedBlock);
    }

    @Test
    void shouldReturnExistingBlockWithoutCreatingOutboxOnIdempotentPath() {
        var blockerId = UUID.randomUUID();
        var blockedId = UUID.randomUUID();
        var existingBlock = new Block(new UserId(blockerId), new UserId(blockedId), Instant.now());

        when(blockRepository.findByUsers(blockerId, blockedId)).thenReturn(Optional.of(existingBlock));
        when(blockEventService.findWaitingBlockEventId(existingBlock)).thenReturn(null);

        BlockPersistenceResult result = blockPersistenceService.createBlockAndUpdateFollows(blockerId, blockedId);

        assertThat(result.created()).isFalse();
        assertThat(result.block()).isEqualTo(existingBlock);
        assertThat(result.outboxEventId()).isNull();
        verify(followRepository).markBidirectionalRelationshipsAsBlocked(blockerId, blockedId);
        verify(blockRepository, never()).save(org.mockito.ArgumentMatchers.any());
        verify(blockEventService).findWaitingBlockEventId(existingBlock);
        verify(blockEventService, never()).storePendingBlockEvent(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void shouldReturnExistingWaitingOutboxEventOnIdempotentRetryPath() {
        var blockerId = UUID.randomUUID();
        var blockedId = UUID.randomUUID();
        var outboxEventId = UUID.randomUUID();
        var existingBlock = new Block(new UserId(blockerId), new UserId(blockedId), Instant.now());

        when(blockRepository.findByUsers(blockerId, blockedId)).thenReturn(Optional.of(existingBlock));
        when(blockEventService.findWaitingBlockEventId(existingBlock)).thenReturn(outboxEventId);

        BlockPersistenceResult result = blockPersistenceService.createBlockAndUpdateFollows(blockerId, blockedId);

        assertThat(result.created()).isFalse();
        assertThat(result.block()).isEqualTo(existingBlock);
        assertThat(result.outboxEventId()).isEqualTo(outboxEventId);
        verify(followRepository).markBidirectionalRelationshipsAsBlocked(blockerId, blockedId);
        verify(blockRepository, never()).save(org.mockito.ArgumentMatchers.any());
        verify(blockEventService).findWaitingBlockEventId(existingBlock);
        verify(blockEventService, never()).storePendingBlockEvent(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void shouldThrowWhenBlockerIdIsNull() {
        assertThatThrownBy(() -> blockPersistenceService.createBlockAndUpdateFollows(null, UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("blockerUserId must not be null");
    }
}
