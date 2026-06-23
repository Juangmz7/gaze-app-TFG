package com.app.socialservice.block.application.service;

import java.util.UUID;

import com.app.socialservice.follow.application.repository.FollowGraphRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class BlockNodeServiceTest {

    @Mock
    private FollowGraphRepository followGraphRepository;

    @InjectMocks
    private BlockNodeService blockNodeService;

    @Test
    void shouldDeleteBidirectionalFollowRelationship() {
        var blockerId = UUID.randomUUID();
        var blockedId = UUID.randomUUID();

        blockNodeService.deleteBidirectionalFollowRelationship(blockerId, blockedId);

        verify(followGraphRepository).deleteBidirectionalFollowRelationship(blockerId, blockedId);
    }

    @Test
    void shouldThrowWhenBlockedUserIdIsNull() {
        assertThatThrownBy(() -> blockNodeService.deleteBidirectionalFollowRelationship(UUID.randomUUID(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("blockedUserId must not be null");
    }
}
