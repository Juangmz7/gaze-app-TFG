package com.app.socialservice.user.application.service;

import java.util.UUID;

import com.app.socialservice.shared.domain.exception.UserNotFoundException;
import com.app.socialservice.user.application.cache.CacheNames;
import com.app.socialservice.user.application.dto.OwnUserProfileResponse;
import com.app.socialservice.user.application.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads a user's own profile, cached per user until a change of its profile or counters
 * evicts it. Whether the user may see it (exists, not banned) is not part of the cached
 * value: UserProfileService checks the account status live on every request, so a ban
 * applies at once. A separate bean so the cache proxy wraps the call.
 */
@Component
@RequiredArgsConstructor
public class OwnUserProfileReader {

    private final UserRepository userRepository;
    private final UserStatsService userStatsService;

    @Cacheable(cacheNames = CacheNames.OWN_PROFILE, key = CacheNames.KEY_BY_USER_ID)
    @Transactional(readOnly = true)
    public OwnUserProfileResponse read(UUID userId) {
        var profile = userRepository.findOwnProfileById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));
        var stats = userStatsService.getStats(userId);

        return new OwnUserProfileResponse(
                userId,
                profile.username(),
                profile.description(),
                profile.socialMedia(),
                stats.followersCount(),
                stats.followingCount(),
                stats.postCount(),
                profile.profilePic()
        );
    }
}
