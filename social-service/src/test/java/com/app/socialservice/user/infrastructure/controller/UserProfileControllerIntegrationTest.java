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

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
                        .with(jwt().jwt(jwt -> jwt.claim("userId", requesterUserId.toString())))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("target-user"))
                .andExpect(jsonPath("$.description").value("Target description"))
                .andExpect(jsonPath("$.socialMedia.github").value("target-user"))
                .andExpect(jsonPath("$.socialMedia.linkedin").value("target-linkedin"))
                .andExpect(jsonPath("$.followerCount").value(12))
                .andExpect(jsonPath("$.followingCount").value(7))
                .andExpect(jsonPath("$.postCount").value(33))
                .andExpect(jsonPath("$.profilePic").value("https://cdn.example.com/target.png"))
                .andExpect(jsonPath("$.following").value(true))
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
                        .with(jwt().jwt(jwt -> jwt.claim("userId", requesterUserId.toString()))))
                .andExpect(status().isForbidden());
    }

    @Test
    void getApiSocialProfileReturns403WhenTargetHasBlockedRequester() throws Exception {
        var requesterUserId = UUID.randomUUID();
        var targetUserId = UUID.randomUUID();

        seedUser(requesterUserId, "requester-blocked", UserAccountStatus.ACCEPTED, null, null, null);
        seedUser(targetUserId, "target-blocker", UserAccountStatus.ACCEPTED, null, null, null);
        seedBlock(targetUserId, requesterUserId);

        mockMvc.perform(get("/api/social/profile/{userId}", targetUserId)
                        .with(jwt().jwt(jwt -> jwt.claim("userId", requesterUserId.toString()))))
                .andExpect(status().isForbidden());
    }

    @Test
    void getApiSocialProfileReturns404WhenTargetDoesNotExist() throws Exception {
        var requesterUserId = UUID.randomUUID();
        var targetUserId = UUID.randomUUID();

        seedUser(requesterUserId, "requester-user", UserAccountStatus.ACCEPTED, null, null, null);

        mockMvc.perform(get("/api/social/profile/{userId}", targetUserId)
                        .with(jwt().jwt(jwt -> jwt.claim("userId", requesterUserId.toString()))))
                .andExpect(status().isNotFound());
    }

    @Test
    void getApiSocialProfileReturns400WhenRequesterRequestsOwnProfile() throws Exception {
        var requesterUserId = UUID.randomUUID();

        seedUser(requesterUserId, "self-user", UserAccountStatus.ACCEPTED, null, null, null);

        mockMvc.perform(get("/api/social/profile/{userId}", requesterUserId)
                        .with(jwt().jwt(jwt -> jwt.claim("userId", requesterUserId.toString()))))
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
}
