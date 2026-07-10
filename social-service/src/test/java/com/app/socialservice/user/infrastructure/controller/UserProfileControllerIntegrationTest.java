package com.app.socialservice.user.infrastructure.controller;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.app.socialservice.TestcontainersConfiguration;
import com.app.socialservice.block.infrastructure.entity.BlockEntity;
import com.app.socialservice.block.infrastructure.entity.BlockEntityId;
import com.app.socialservice.block.infrastructure.repository.JpaBlockRepository;
import com.app.socialservice.follow.infrastructure.entity.FollowEntity;
import com.app.socialservice.follow.infrastructure.entity.FollowEntityId;
import com.app.socialservice.follow.infrastructure.enums.FollowStatus;
import com.app.socialservice.follow.infrastructure.repository.JpaFollowRepository;
import com.app.socialservice.user.domain.enums.UserAccountStatus;
import com.app.socialservice.user.infrastructure.entity.UserBioEmbeddable;
import com.app.socialservice.user.infrastructure.entity.UserEntity;
import com.app.socialservice.user.infrastructure.repository.JpaUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.nullValue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class UserProfileControllerIntegrationTest {

    private static final String USER_STATS_KEY_PATTERN = "user:stats:%s:%s";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JpaUserRepository jpaUserRepository;

    @Autowired
    private JpaFollowRepository jpaFollowRepository;

    @Autowired
    private JpaBlockRepository jpaBlockRepository;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void setUp() {
        jpaBlockRepository.deleteAll();
        jpaFollowRepository.deleteAll();
        jpaUserRepository.deleteAll();
        flushRedis();
    }

    @Test
    void getApiSocialProfileReturns200WithProfileData() throws Exception {
        var requesterUserId = UUID.randomUUID();
        var targetUserId = UUID.randomUUID();

        seedUser(requesterUserId, "requester-user", UserAccountStatus.ACCEPTED, null, null, null);
        seedUser(
                targetUserId,
                "target-user",
                UserAccountStatus.ACCEPTED,
                "Target description",
                Map.of("github", "target-user", "linkedin", "target-linkedin"),
                "https://cdn.example.com/target.png"
        );
        seedFollow(requesterUserId, targetUserId, FollowStatus.ACTIVE);
        seedUserStats(targetUserId, 12L, 7L, 33L);

        mockMvc.perform(get("/api/social/profile/{userId}", targetUserId)
                        .with(jwt().jwt(jwt -> jwt.subject(requesterUserId.toString())))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(targetUserId.toString()))
                .andExpect(jsonPath("$.username").value("target-user"))
                .andExpect(jsonPath("$.description").value("Target description"))
                .andExpect(jsonPath("$.socialMedia.github").value("target-user"))
                .andExpect(jsonPath("$.socialMedia.linkedin").value("target-linkedin"))
                .andExpect(jsonPath("$.followerCount").value(12))
                .andExpect(jsonPath("$.followingCount").value(7))
                .andExpect(jsonPath("$.postCount").value(33))
                .andExpect(jsonPath("$.profilePic").value("https://cdn.example.com/target.png"))
                .andExpect(jsonPath("$.following").value(true))
                .andExpect(jsonPath("$.followsMe").value(false))
                .andExpect(jsonPath("$.isBanned").value(false));
    }
    
    @Test
    void getApiSocialProfileMeReturnsAuthenticatedUserProfileUsingRedisCounters() throws Exception {
        var userId = UUID.randomUUID();

        seedUser(UserEntity.builder()
                .id(userId)
                .username("profile-user")
                .email("profile-user@example.com")
                .pictureUrl("https://example.com/profile-user.png")
                .bio(UserBioEmbeddable.builder()
                        .description("Own bio")
                        .socialMedia(Map.of("github", "profile-user"))
                        .build())
                .postCount(5L)
                .accountStatus(UserAccountStatus.ACCEPTED)
                .build());
        seedCounter(userId, "followers", 8L);
        seedCounter(userId, "following", 3L);

        mockMvc.perform(get("/api/social/profile/me")
                        .with(jwt().jwt(jwt -> jwt.subject(userId.toString())))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("profile-user"))
                .andExpect(jsonPath("$.description").value("Own bio"))
                .andExpect(jsonPath("$.socialMedia.github").value("profile-user"))
                .andExpect(jsonPath("$.followersCount").value(8))
                .andExpect(jsonPath("$.followingCount").value(3))
                .andExpect(jsonPath("$.postCount").value(5))
                .andExpect(jsonPath("$.profilePicture").value("https://example.com/profile-user.png"))
                .andExpect(jsonPath("$.isBanned").value(false));
    }

    @Test
    void getApiSocialProfileReturns403WhenRequesterHasBlockedTarget() throws Exception {
        var requesterUserId = UUID.randomUUID();
        var targetUserId = UUID.randomUUID();

        seedUser(requesterUserId, "requester-blocker", UserAccountStatus.ACCEPTED, null, null, null);
        seedUser(targetUserId, "target-blocked", UserAccountStatus.ACCEPTED, null, null, null);
        seedBlock(requesterUserId, targetUserId);

        mockMvc.perform(get("/api/social/profile/{userId}", targetUserId)
                        .with(jwt().jwt(jwt -> jwt.subject(requesterUserId.toString()))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("BLOCKED"));
    }

    @Test
    void getApiSocialProfileReturns403WhenTargetHasBlockedRequester() throws Exception {
        var requesterUserId = UUID.randomUUID();
        var targetUserId = UUID.randomUUID();

        seedUser(requesterUserId, "requester-blocked", UserAccountStatus.ACCEPTED, null, null, null);
        seedUser(targetUserId, "target-blocker", UserAccountStatus.ACCEPTED, null, null, null);
        seedBlock(targetUserId, requesterUserId);

        mockMvc.perform(get("/api/social/profile/{userId}", targetUserId)
                        .with(jwt().jwt(jwt -> jwt.subject(requesterUserId.toString()))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("BLOCKED"));
    }

    @Test
    void getApiSocialProfileReturns404WhenTargetDoesNotExist() throws Exception {
        var requesterUserId = UUID.randomUUID();
        var targetUserId = UUID.randomUUID();

        seedUser(requesterUserId, "requester-user", UserAccountStatus.ACCEPTED, null, null, null);

        mockMvc.perform(get("/api/social/profile/{userId}", targetUserId)
                        .with(jwt().jwt(jwt -> jwt.subject(requesterUserId.toString()))))
                .andExpect(status().isNotFound());
    }

    @Test
    void getApiSocialProfileReturns400WhenRequesterRequestsOwnProfile() throws Exception {
        var requesterUserId = UUID.randomUUID();

        seedUser(requesterUserId, "self-user", UserAccountStatus.ACCEPTED, null, null, null);

        mockMvc.perform(get("/api/social/profile/{userId}", requesterUserId)
                        .with(jwt().jwt(jwt -> jwt.subject(requesterUserId.toString()))))
                .andExpect(status().isBadRequest());
    }

    private void seedUser(
            UUID userId,
            String username,
            UserAccountStatus status,
            String description,
            Map<String, String> socialMedia,
            String pictureUrl
    ) {
        UserBioEmbeddable bio = null;
        if (description != null || socialMedia != null) {
            bio = UserBioEmbeddable.builder()
                    .description(description)
                    .socialMedia(socialMedia)
                    .build();
        }

        jpaUserRepository.save(UserEntity.builder()
                .id(userId)
                .username(username)
                .email(username + "@example.com")
                .accountStatus(status)
                .pictureUrl(pictureUrl)
                .bio(bio)
                .build());
    }

    private void seedFollow(UUID followerUserId, UUID followedUserId, FollowStatus status) {
        jpaFollowRepository.save(new FollowEntity(
                new FollowEntityId(followerUserId, followedUserId),
                status,
                Instant.now(),
                Instant.now()
        ));
    }

    private void seedBlock(UUID blockerUserId, UUID blockedUserId) {
        jpaBlockRepository.save(new BlockEntity(
                new BlockEntityId(blockerUserId, blockedUserId),
                Instant.now()
        ));
    }

    private void seedUserStats(UUID userId, long followerCount, long followingCount, long postCount) {
        stringRedisTemplate.opsForValue().set(buildCounterKey(userId, "followers"), String.valueOf(followerCount));
        stringRedisTemplate.opsForValue().set(buildCounterKey(userId, "following"), String.valueOf(followingCount));
        stringRedisTemplate.opsForValue().set(buildCounterKey(userId, "postCount"), String.valueOf(postCount));
    }

    @Test
    void getApiSocialProfileMeReturnsNullableFieldsAndBannedFlag() throws Exception {
        var userId = UUID.randomUUID();

        seedUser(UserEntity.builder()
                .id(userId)
                .username("banned-user")
                .email("banned-user@example.com")
                .postCount(0L)
                .accountStatus(UserAccountStatus.BANNED)
                .build());

        mockMvc.perform(get("/api/social/profile/me")
                        .with(jwt().jwt(jwt -> jwt.subject(userId.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("banned-user"))
                .andExpect(jsonPath("$.description").value(nullValue()))
                .andExpect(jsonPath("$.socialMedia").value(nullValue()))
                .andExpect(jsonPath("$.followersCount").value(0))
                .andExpect(jsonPath("$.followingCount").value(0))
                .andExpect(jsonPath("$.postCount").value(0))
                .andExpect(jsonPath("$.profilePicture").value(nullValue()))
                .andExpect(jsonPath("$.isBanned").value(true));
    }

    @Test
    void getApiSocialProfileMeReturns404WhenAuthenticatedUserDoesNotExist() throws Exception {
        var userId = UUID.randomUUID();

        mockMvc.perform(get("/api/social/profile/me")
                        .with(jwt().jwt(jwt -> jwt.subject(userId.toString()))))
                .andExpect(status().isNotFound());

        assertThat(jpaUserRepository.count()).isZero();
    }

    @Test
    void getApiSocialProfileMeReturns401WhenJwtDoesNotContainUserIdClaim() throws Exception {
        mockMvc.perform(get("/api/social/profile/me")
                        .with(jwt()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("INVALID_JWT"));
    }

    private void seedUser(UserEntity userEntity) {
        jpaUserRepository.save(userEntity);
    }

    private void seedCounter(UUID userId, String counterName, long value) {
        stringRedisTemplate.opsForValue().set(buildCounterKey(userId, counterName), String.valueOf(value));
    }

    private void flushRedis() {
        var connection = stringRedisTemplate.getConnectionFactory().getConnection();
        try {
            connection.serverCommands().flushDb();
        } finally {
            connection.close();
        }
    }

    private String buildCounterKey(UUID userId, String counterName) {
        return String.format(USER_STATS_KEY_PATTERN, userId, counterName);
    }

    @Test
    void putApiSocialProfileUpdatesAuthenticatedUsersOwnProfile() throws Exception {
        var authenticatedUserId = UUID.randomUUID();
        var otherUserId = UUID.randomUUID();

        seedUser(authenticatedUserId, "profile-owner", "Owner bio", "https://cdn.example.com/owner-old.png");
        seedUser(otherUserId, "other-user", "Other bio", "https://cdn.example.com/other.png");

        mockMvc.perform(put("/api/social/profile")
                        .with(jwt().jwt(jwt -> jwt.subject(authenticatedUserId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "description": "Updated owner bio",
                                  "profilePicture": "https://cdn.example.com/owner-new.png",
                                  "socialMedia": {
                                    "github": "owner-new"
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(authenticatedUserId.toString()))
                .andExpect(jsonPath("$.description").value("Updated owner bio"))
                .andExpect(jsonPath("$.profilePicture").value("https://cdn.example.com/owner-new.png"))
                .andExpect(jsonPath("$.socialMedia.github").value("owner-new"));

        assertThat(jpaUserRepository.findById(authenticatedUserId)).get()
                .extracting(UserEntity::getPictureUrl)
                .isEqualTo("https://cdn.example.com/owner-new.png");
        assertThat(jpaUserRepository.findById(authenticatedUserId)).get()
                .extracting(entity -> entity.getBio().getDescription())
                .isEqualTo("Updated owner bio");
        assertThat(jpaUserRepository.findById(authenticatedUserId)).get()
                .extracting(entity -> entity.getBio().getSocialMedia())
                .isEqualTo(Map.of("github", "owner-new"));

        assertThat(jpaUserRepository.findById(otherUserId)).get()
                .extracting(UserEntity::getPictureUrl)
                .isEqualTo("https://cdn.example.com/other.png");
        assertThat(jpaUserRepository.findById(otherUserId)).get()
                .extracting(entity -> entity.getBio().getDescription())
                .isEqualTo("Other bio");
    }

    @Test
    void putApiSocialProfileReturnsCurrentDataWithoutPersistingWhenPayloadIsUnchanged() throws Exception {
        var authenticatedUserId = UUID.randomUUID();

        var seededUser = seedUser(
                authenticatedUserId,
                "same-user",
                "Same bio",
                "https://cdn.example.com/same.png"
        );
        var versionBefore = jpaUserRepository.findById(authenticatedUserId).orElseThrow().getVersion();

        mockMvc.perform(put("/api/social/profile")
                        .with(jwt().jwt(jwt -> jwt.subject(authenticatedUserId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "description": "Same bio",
                                  "profilePicture": "https://cdn.example.com/same.png",
                                  "socialMedia": {
                                    "github": "same-user"
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(authenticatedUserId.toString()))
                .andExpect(jsonPath("$.description").value("Same bio"))
                .andExpect(jsonPath("$.profilePicture").value("https://cdn.example.com/same.png"))
                .andExpect(jsonPath("$.socialMedia.github").value("same-user"));

        var userAfter = jpaUserRepository.findById(authenticatedUserId).orElseThrow();
        assertThat(userAfter.getVersion()).isEqualTo(versionBefore);
        assertThat(userAfter.getUpdatedAt()).isEqualTo(seededUser.getUpdatedAt());
    }

    @Test
    void putApiSocialProfileReturnsBadRequestWhenDescriptionExceedsPersistenceLimit() throws Exception {
        var authenticatedUserId = UUID.randomUUID();

        seedUser(
                authenticatedUserId,
                "invalid-user",
                "Existing bio",
                "https://cdn.example.com/existing.png"
        );

        mockMvc.perform(put("/api/social/profile")
                        .with(jwt().jwt(jwt -> jwt.subject(authenticatedUserId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "description": "%s"
                                }
                                """.formatted("a".repeat(256))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.path").value("/api/social/profile"))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void putApiSocialProfileReturnsBadRequestWhenProfilePictureExceedsPersistenceLimit() throws Exception {
        var authenticatedUserId = UUID.randomUUID();

        seedUser(
                authenticatedUserId,
                "invalid-user",
                "Existing bio",
                "https://cdn.example.com/existing.png"
        );

        mockMvc.perform(put("/api/social/profile")
                        .with(jwt().jwt(jwt -> jwt.subject(authenticatedUserId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "profilePicture": "%s"
                                }
                                """.formatted("https://%s".formatted("a".repeat(248)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.path").value("/api/social/profile"))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void putApiSocialProfileIgnoresSpoofedBodyUserIdAndUpdatesAuthenticatedUserOnly() throws Exception {
        var authenticatedUserId = UUID.randomUUID();
        var spoofedUserId = UUID.randomUUID();

        seedUser(authenticatedUserId, "profile-owner", "Owner bio", "https://cdn.example.com/owner-old.png");
        seedUser(spoofedUserId, "spoof-target", "Spoof target bio", "https://cdn.example.com/spoof-old.png");

        mockMvc.perform(put("/api/social/profile")
                        .with(jwt().jwt(jwt -> jwt.subject(authenticatedUserId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "userId": "%s",
                                  "description": "Updated owner bio",
                                  "profilePicture": "https://cdn.example.com/owner-new.png",
                                  "socialMedia": {
                                    "github": "owner-new"
                                  }
                                }
                                """.formatted(spoofedUserId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(authenticatedUserId.toString()))
                .andExpect(jsonPath("$.description").value("Updated owner bio"))
                .andExpect(jsonPath("$.profilePicture").value("https://cdn.example.com/owner-new.png"))
                .andExpect(jsonPath("$.socialMedia.github").value("owner-new"));

        assertThat(jpaUserRepository.findById(authenticatedUserId)).get()
                .extracting(UserEntity::getPictureUrl)
                .isEqualTo("https://cdn.example.com/owner-new.png");
        assertThat(jpaUserRepository.findById(authenticatedUserId)).get()
                .extracting(entity -> entity.getBio().getDescription())
                .isEqualTo("Updated owner bio");

        assertThat(jpaUserRepository.findById(spoofedUserId)).get()
                .extracting(UserEntity::getPictureUrl)
                .isEqualTo("https://cdn.example.com/spoof-old.png");
        assertThat(jpaUserRepository.findById(spoofedUserId)).get()
                .extracting(entity -> entity.getBio().getDescription())
                .isEqualTo("Spoof target bio");
    }

    private UserEntity seedUser(UUID userId, String username, String description, String pictureUrl) {
        return jpaUserRepository.save(UserEntity.builder()
                .id(userId)
                .username(username)
                .email(username + "@example.com")
                .pictureUrl(pictureUrl)
                .bio(UserBioEmbeddable.builder()
                        .description(description)
                        .socialMedia(Map.of("github", username))
                        .build())
                .accountStatus(UserAccountStatus.ACCEPTED)
                .build());
    }
}
