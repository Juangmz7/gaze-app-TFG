package com.app.socialservice.user.application.service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.app.socialservice.TestcontainersConfiguration;
import com.app.socialservice.user.application.cache.CacheNames;
import com.app.socialservice.user.application.dto.OwnUserProfileResponse;
import com.app.socialservice.user.application.dto.PublicUserProfile;
import com.app.socialservice.user.application.dto.RecommendedUserResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/** The user caches against the real Redis: what goes in comes back with its type. */
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class UserCachesIntegrationTest {

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @jakarta.annotation.Resource
    private CacheManager cacheManager;

    @jakarta.annotation.Resource
    private UserProfileService userProfileService;

    @jakarta.annotation.Resource
    private PublicUserProfileReader publicUserProfileReader;

    @jakarta.annotation.Resource
    private UserCacheEvictor userCacheEvictor;

    @jakarta.annotation.Resource
    private JdbcTemplate jdbcTemplate;

    @jakarta.annotation.Resource
    private PlatformTransactionManager transactionManager;

    @Test
    void shouldReadEveryCachedTypeBackFromRedis() {
        var userId = UUID.randomUUID();
        var key = CacheNames.userKey(userId);
        var socialMedia = Map.of("github", "someone");
        var ownProfile = new OwnUserProfileResponse(userId, "someone", "bio", socialMedia, 1, 2, 3, "https://a/b.png");
        var publicProfile = new PublicUserProfile(userId, "someone", "bio", socialMedia, "https://a/b.png", 1, 2, 3);
        var recommended = List.of(new RecommendedUserResponse(UUID.randomUUID(), "other", null, null, true));

        cacheManager.getCache(CacheNames.OWN_PROFILE).put(key, ownProfile);
        cacheManager.getCache(CacheNames.PUBLIC_PROFILE).put(key, publicProfile);
        cacheManager.getCache(CacheNames.RECOMMENDED_USERS).put(key, recommended);

        assertThat(cacheManager.getCache(CacheNames.OWN_PROFILE).get(key, OwnUserProfileResponse.class))
                .isEqualTo(ownProfile);
        assertThat(cacheManager.getCache(CacheNames.PUBLIC_PROFILE).get(key, PublicUserProfile.class))
                .isEqualTo(publicProfile);
        assertThat(cacheManager.getCache(CacheNames.RECOMMENDED_USERS).get(key).get()).isEqualTo(recommended);
    }

    @Test
    void shouldServeTheOwnProfileFromTheCacheUntilAChangeEvictsIt() {
        var userId = insertUser("cached-name");
        assertThat(userProfileService.getOwnProfile(userId).username()).isEqualTo("cached-name");

        // Changed behind the service's back: the cached profile is still served
        jdbcTemplate.update("UPDATE users SET username = 'new-name' WHERE id = ?", userId);
        assertThat(userProfileService.getOwnProfile(userId).username()).isEqualTo("cached-name");

        userCacheEvictor.evictProfiles(userId);
        assertThat(userProfileService.getOwnProfile(userId).username()).isEqualTo("new-name");
    }

    @Test
    void shouldServeThePublicProfileFromTheCacheUntilAChangeEvictsIt() {
        var userId = insertUser("public-name");
        assertThat(publicUserProfileReader.read(userId).username()).isEqualTo("public-name");

        jdbcTemplate.update("UPDATE users SET username = 'renamed' WHERE id = ?", userId);
        assertThat(publicUserProfileReader.read(userId).username()).isEqualTo("public-name");

        userCacheEvictor.evictProfiles(userId);
        assertThat(publicUserProfileReader.read(userId).username()).isEqualTo("renamed");
    }

    @Test
    void shouldEvictOnlyAfterTheTransactionCommits() {
        var userId = UUID.randomUUID();
        var key = CacheNames.userKey(userId);
        var cache = cacheManager.getCache(CacheNames.PUBLIC_PROFILE);
        cache.put(key, new PublicUserProfile(userId, "u", null, null, null, 0, 0, 0));

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            userCacheEvictor.evictProfiles(userId);
            // A read before the commit would cache the old state again: not evicted yet
            assertThat(cache.get(key)).isNotNull();
        });

        assertThat(cache.get(key)).isNull();
    }

    private UUID insertUser(String username) {
        var userId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO users (id, username, email, account_status, post_count, version, created_at)
                VALUES (?, ?, ?, 'ACCEPTED', 0, 0, ?)
                """, userId, username, username + "@example.com", Timestamp.from(Instant.now()));
        return userId;
    }
}
