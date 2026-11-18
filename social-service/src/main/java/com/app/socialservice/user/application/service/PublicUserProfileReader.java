package com.app.socialservice.user.application.service;

import java.util.UUID;

import com.app.socialservice.user.application.cache.CacheNames;
import com.app.socialservice.user.application.dto.PublicUserProfile;
import com.app.socialservice.user.application.repository.UserRepository;
import com.app.socialservice.user.domain.exception.UserProfileNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads the part of a public profile that is the same whoever asks, cached per user.
 * A separate bean from UserProfileService so the cache proxy wraps the call. A missing,
 * deleted or banned user throws, and an exception is never cached.
 */
@Component
@RequiredArgsConstructor
public class PublicUserProfileReader {

    private final UserRepository userRepository;
    private final UserStatsService userStatsService;

    @Cacheable(cacheNames = CacheNames.PUBLIC_PROFILE, key = CacheNames.KEY_BY_TARGET_USER_ID)
    @Transactional(readOnly = true)
    public PublicUserProfile read(UUID targetUserId) {
        var profile = userRepository.findPublicProfileData(targetUserId)
                .filter(data -> !data.banned())
                .orElseThrow(() -> new UserProfileNotFoundException(targetUserId));
        var stats = userStatsService.getStats(targetUserId);

        return new PublicUserProfile(
                profile.userId(),
                profile.username(),
                profile.description(),
                profile.socialMedia(),
                profile.profilePic(),
                stats.followersCount(),
                stats.followingCount(),
                stats.postCount()
        );
    }
}
