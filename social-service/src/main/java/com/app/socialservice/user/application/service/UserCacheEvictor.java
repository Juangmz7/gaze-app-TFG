package com.app.socialservice.user.application.service;

import java.util.UUID;

import com.app.socialservice.user.application.cache.CacheNames;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

/**
 * Evicts the cached entries of the users a change touched. The cache manager is
 * transaction aware: inside a transaction the eviction runs after the commit, so a
 * read between the two cannot cache the old state again.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserCacheEvictor {

    private final CacheManager cacheManager;

    /** A profile or a counter of these users changed: their own and public profiles. */
    public void evictProfiles(UUID... userIds) {
        evict(CacheNames.OWN_PROFILE, userIds);
        evict(CacheNames.PUBLIC_PROFILE, userIds);
    }

    /** Only the public profiles: for a change whose caller replaces the own profile itself. */
    public void evictPublicProfiles(UUID... userIds) {
        evict(CacheNames.PUBLIC_PROFILE, userIds);
    }

    /** The follows or blocks of these users changed: the users recommended to them. */
    public void evictRecommendations(UUID... userIds) {
        evict(CacheNames.RECOMMENDED_USERS, userIds);
    }

    private void evict(String cacheName, UUID... userIds) {
        var cache = cacheManager.getCache(cacheName);
        if (cache == null) {
            return;
        }
        for (var userId : userIds) {
            if (userId != null) {
                cache.evict(CacheNames.userKey(userId));
            }
        }
        log.debug("Evicted cache {} for users {}", cacheName, userIds);
    }
}
