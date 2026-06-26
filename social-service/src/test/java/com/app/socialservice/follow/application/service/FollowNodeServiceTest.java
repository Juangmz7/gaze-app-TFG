package com.app.socialservice.follow.application.service;

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
class FollowNodeServiceTest {

    @Mock
    private FollowGraphRepository followGraphRepository;

    @InjectMocks
    private FollowNodeService followNodeService;

    @Test
    void shouldCreateFollowRelationship() {
        var followerId = UUID.randomUUID();
        var followedId = UUID.randomUUID();

        followNodeService.createFollowRelationship(followerId, followedId);

        verify(followGraphRepository).createFollowRelationship(followerId, followedId);
    }

    @Test
    void shouldDeleteFollowRelationship() {
        var followerId = UUID.randomUUID();
        var followedId = UUID.randomUUID();

        followNodeService.deleteFollowRelationship(followerId, followedId);

        verify(followGraphRepository).deleteFollowRelationship(followerId, followedId);
    }

    @Test
    void shouldThrowWhenFollowerAndFollowedUsersMatch() {
        var userId = UUID.randomUUID();

        assertThatThrownBy(() -> followNodeService.createFollowRelationship(userId, userId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("followerUserId must not equal followedUserId");
    }

    @Test
    void shouldThrowWhenDeletingAndFollowerAndFollowedUsersMatch() {
        var userId = UUID.randomUUID();

        assertThatThrownBy(() -> followNodeService.deleteFollowRelationship(userId, userId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("followerUserId must not equal followedUserId");
    }
}
