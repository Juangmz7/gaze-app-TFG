package com.app.socialservice.user.application.service;

import java.util.UUID;

import com.app.socialservice.user.application.cache.CacheNames;
import com.app.socialservice.user.application.repository.UserStatsRepository;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserStatsServiceTest {

    @Mock
    private UserStatsRepository userStatsRepository;

    @InjectMocks
    private UserStatsService userStatsService;

    @Nested
    class CacheAnnotationsTest {

        @Test
        void shouldNotCacheRawStatReads() throws NoSuchMethodException {
            assertNotCacheable("getFollowersCount");
            assertNotCacheable("getFollowingCount");
            assertNotCacheable("getPostCount");
        }

        @Test
        void shouldEvictRelatedCachesWhenFollowCountersChange() throws NoSuchMethodException {
            var method = UserStatsService.class.getMethod("incrementFollowCounters", UUID.class, UUID.class);
            var caching = method.getAnnotation(Caching.class);

            assertThat(caching).isNotNull();
            assertThat(caching.evict()).hasSize(3);
            assertEvict(caching.evict()[0], CacheNames.OWN_PROFILE, CacheNames.OWN_PROFILE_KEY_BY_FOLLOWER_USER_ID, false);
            assertEvict(caching.evict()[1], CacheNames.OWN_PROFILE, CacheNames.OWN_PROFILE_KEY_BY_FOLLOWED_USER_ID, false);
            assertEvict(caching.evict()[2], CacheNames.PUBLIC_PROFILE, "", true);
        }

        @Test
        void shouldEvictRelatedCachesWhenPostCountChanges() throws NoSuchMethodException {
            var method = UserStatsService.class.getMethod("incrementPostCount", UUID.class);
            var caching = method.getAnnotation(Caching.class);

            assertThat(caching).isNotNull();
            assertThat(caching.evict()).hasSize(2);
            assertEvict(caching.evict()[0], CacheNames.OWN_PROFILE, CacheNames.OWN_PROFILE_KEY_BY_USER_ID, false);
            assertEvict(caching.evict()[1], CacheNames.PUBLIC_PROFILE, "", true);
        }

        private void assertNotCacheable(String methodName) throws NoSuchMethodException {
            var method = UserStatsService.class.getMethod(methodName, UUID.class);
            var cacheable = method.getAnnotationsByType(org.springframework.cache.annotation.Cacheable.class);

            assertThat(cacheable).isEmpty();
        }

        private void assertEvict(CacheEvict cacheEvict, String cacheName, String key, boolean allEntries) {
            assertThat(cacheEvict.cacheNames()).containsExactly(cacheName);
            assertThat(cacheEvict.allEntries()).isEqualTo(allEntries);
            if (!allEntries) {
                assertThat(cacheEvict.key()).isEqualTo(key);
            }
        }
    }

    @Test
    void shouldGetFollowersCountUsingRepositoryCounter() {
        var userId = UUID.randomUUID();

        when(userStatsRepository.getFollowersCount(userId)).thenReturn(11L);

        var count = userStatsService.getFollowersCount(userId);

        assertThat(count).isEqualTo(11L);
        verify(userStatsRepository).getFollowersCount(userId);
    }

    @Test
    void shouldGetFollowingCountUsingRepositoryCounter() {
        var userId = UUID.randomUUID();

        when(userStatsRepository.getFollowingCount(userId)).thenReturn(7L);

        var count = userStatsService.getFollowingCount(userId);

        assertThat(count).isEqualTo(7L);
        verify(userStatsRepository).getFollowingCount(userId);
    }

    @Test
    void shouldGetPostCountUsingRepositoryCounter() {
        var userId = UUID.randomUUID();

        when(userStatsRepository.getPostCount(userId)).thenReturn(19L);

        var count = userStatsService.getPostCount(userId);

        assertThat(count).isEqualTo(19L);
        verify(userStatsRepository).getPostCount(userId);
    }

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
