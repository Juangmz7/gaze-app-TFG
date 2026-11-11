package com.app.socialservice.user.application.service;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.app.socialservice.user.application.cache.CacheNames;
import com.app.socialservice.user.application.dto.PublicUserProfile;
import com.app.socialservice.user.application.dto.PublicUserProfileData;
import com.app.socialservice.user.application.dto.UserStats;
import com.app.socialservice.user.application.repository.UserRepository;
import com.app.socialservice.user.domain.exception.UserProfileNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.annotation.Cacheable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PublicUserProfileReaderTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserStatsService userStatsService;

    @InjectMocks
    private PublicUserProfileReader publicUserProfileReader;

    @Test
    void shouldBeCachedPerTargetUser() throws NoSuchMethodException {
        var cacheable = PublicUserProfileReader.class.getMethod("read", UUID.class).getAnnotation(Cacheable.class);

        assertThat(cacheable.cacheNames()).containsExactly(CacheNames.PUBLIC_PROFILE);
        assertThat(cacheable.key()).isEqualTo(CacheNames.KEY_BY_TARGET_USER_ID);
    }

    @Test
    void shouldCombineTheProfileWithItsStats() {
        var userId = UUID.randomUUID();
        when(userRepository.findPublicProfileData(userId)).thenReturn(Optional.of(new PublicUserProfileData(
                userId, "someone", "bio", Map.of("github", "someone"), "https://a/b.png", false)));
        when(userStatsService.getStats(userId)).thenReturn(new UserStats(4, 5, 6));

        assertThat(publicUserProfileReader.read(userId)).isEqualTo(new PublicUserProfile(
                userId, "someone", "bio", Map.of("github", "someone"), "https://a/b.png", 4, 5, 6));
    }

    @Test
    void shouldThrowForAMissingOrDeletedUser() {
        var userId = UUID.randomUUID();
        when(userRepository.findPublicProfileData(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> publicUserProfileReader.read(userId))
                .isInstanceOf(UserProfileNotFoundException.class)
                .hasMessageContaining(userId.toString());
        verifyNoInteractions(userStatsService);
    }

    @Test
    void shouldThrowForABannedUserSoItIsNeverCached() {
        var userId = UUID.randomUUID();
        when(userRepository.findPublicProfileData(userId)).thenReturn(Optional.of(new PublicUserProfileData(
                userId, "banned", null, null, null, true)));

        assertThatThrownBy(() -> publicUserProfileReader.read(userId))
                .isInstanceOf(UserProfileNotFoundException.class);
        verifyNoInteractions(userStatsService);
    }
}
