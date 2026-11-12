package com.app.socialservice.user.application.service;

import java.util.UUID;

import com.app.socialservice.user.application.dto.UserStats;
import com.app.socialservice.user.application.repository.UserStatsRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserStatsServiceTest {

    @Mock
    private UserStatsRepository userStatsRepository;

    @Mock
    private UserCacheEvictor userCacheEvictor;

    @InjectMocks
    private UserStatsService userStatsService;

    @Test
    void shouldReturnTheStatsOfTheRepository() {
        var userId = UUID.randomUUID();
        var stats = new UserStats(3, 2, 7);
        when(userStatsRepository.getStats(userId)).thenReturn(stats);

        assertThat(userStatsService.getStats(userId)).isEqualTo(stats);
    }

    @Test
    void shouldNotCacheTheRawStats() throws NoSuchMethodException {
        // The profiles that include them are cached, and evicted when they change
        var method = UserStatsService.class.getMethod("getStats", UUID.class);

        assertThat(method.getAnnotation(Cacheable.class)).isNull();
        assertThat(method.getAnnotation(Caching.class)).isNull();
        assertThat(method.getAnnotation(CacheEvict.class)).isNull();
    }

    @Test
    void shouldChangeThePostCountThenEvictTheUsersProfiles() {
        var userId = UUID.randomUUID();

        userStatsService.incrementPostCount(userId);
        userStatsService.decrementPostCount(userId);

        InOrder inOrder = inOrder(userStatsRepository, userCacheEvictor);
        inOrder.verify(userStatsRepository).incrementPostCount(userId);
        inOrder.verify(userCacheEvictor).evictProfiles(userId);
        inOrder.verify(userStatsRepository).decrementPostCount(userId);
        inOrder.verify(userCacheEvictor).evictProfiles(userId);
    }

    @Test
    void shouldRejectANullUserId() {
        assertThatThrownBy(() -> userStatsService.getStats(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("userId must not be null");
        assertThatThrownBy(() -> userStatsService.incrementPostCount(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> userStatsService.decrementPostCount(null))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(userStatsRepository, userCacheEvictor);
    }
}
