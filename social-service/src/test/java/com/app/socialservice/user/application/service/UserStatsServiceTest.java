package com.app.socialservice.user.application.service;

import java.util.UUID;

import com.app.socialservice.user.application.repository.UserStatsRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class UserStatsServiceTest {

    @Mock
    private UserStatsRepository userStatsRepository;

    @InjectMocks
    private UserStatsService userStatsService;

    @Test
    void shouldIncrementFollowCountersUsingRepositoryCounters() {
        var followerUserId = UUID.randomUUID();
        var followedUserId = UUID.randomUUID();

        userStatsService.incrementFollowCounters(followerUserId, followedUserId);

        InOrder inOrder = inOrder(userStatsRepository);
        inOrder.verify(userStatsRepository).incrementFollowingCount(followerUserId);
        inOrder.verify(userStatsRepository).incrementFollowersCount(followedUserId);
    }

    @Test
    void shouldDecrementFollowCountersUsingRepositoryCounters() {
        var followerUserId = UUID.randomUUID();
        var followedUserId = UUID.randomUUID();

        userStatsService.decrementFollowCounters(followerUserId, followedUserId);

        InOrder inOrder = inOrder(userStatsRepository);
        inOrder.verify(userStatsRepository).decrementFollowingCount(followerUserId);
        inOrder.verify(userStatsRepository).decrementFollowersCount(followedUserId);
    }

    @Test
    void shouldIncrementPostCountUsingRepositoryCounter() {
        var userId = UUID.randomUUID();

        userStatsService.incrementPostCount(userId);

        verify(userStatsRepository).incrementPostCount(userId);
    }

    @Test
    void shouldDecrementPostCountUsingRepositoryCounter() {
        var userId = UUID.randomUUID();

        userStatsService.decrementPostCount(userId);

        verify(userStatsRepository).decrementPostCount(userId);
    }

    @Test
    void shouldRejectNullFollowerUserIdWhenIncrementingFollowCounters() {
        assertThatThrownBy(() -> userStatsService.incrementFollowCounters(null, UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("followerUserId must not be null");

        verifyNoInteractions(userStatsRepository);
    }

    @Test
    void shouldRejectSameUserIdsWhenIncrementingFollowCounters() {
        var userId = UUID.randomUUID();

        assertThatThrownBy(() -> userStatsService.incrementFollowCounters(userId, userId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("followerUserId must not equal followedUserId");

        verifyNoInteractions(userStatsRepository);
    }

    @Test
    void shouldRejectNullUserIdWhenIncrementingPostCount() {
        assertThatThrownBy(() -> userStatsService.incrementPostCount(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("userId must not be null");

        verifyNoInteractions(userStatsRepository);
    }
}
