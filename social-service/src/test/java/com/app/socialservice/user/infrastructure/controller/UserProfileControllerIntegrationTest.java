package com.app.socialservice.user.infrastructure.controller;

import java.util.Map;
import java.util.UUID;

import com.app.socialservice.TestcontainersConfiguration;
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
    private StringRedisTemplate stringRedisTemplate;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void setUp() {
        jpaUserRepository.deleteAll();
        flushRedis();
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
                        .with(jwt().jwt(jwt -> jwt.claim("userId", userId.toString())))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("profile-user"))
                .andExpect(jsonPath("$.description").value("Own bio"))
                .andExpect(jsonPath("$.socialMedia.github").value("profile-user"))
                .andExpect(jsonPath("$.followersCount").value(8))
                .andExpect(jsonPath("$.followingCount").value(3))
                .andExpect(jsonPath("$.postCount").value(5))
                .andExpect(jsonPath("$.profilePic").value("https://example.com/profile-user.png"))
                .andExpect(jsonPath("$.isBanned").value(false));
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
                        .with(jwt().jwt(jwt -> jwt.claim("userId", userId.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("banned-user"))
                .andExpect(jsonPath("$.description").value(nullValue()))
                .andExpect(jsonPath("$.socialMedia").value(nullValue()))
                .andExpect(jsonPath("$.followersCount").value(0))
                .andExpect(jsonPath("$.followingCount").value(0))
                .andExpect(jsonPath("$.postCount").value(0))
                .andExpect(jsonPath("$.profilePic").value(nullValue()))
                .andExpect(jsonPath("$.isBanned").value(true));
    }

    @Test
    void getApiSocialProfileMeReturns404WhenAuthenticatedUserDoesNotExist() throws Exception {
        var userId = UUID.randomUUID();

        mockMvc.perform(get("/api/social/profile/me")
                        .with(jwt().jwt(jwt -> jwt.claim("userId", userId.toString()))))
                .andExpect(status().isNotFound());

        assertThat(jpaUserRepository.count()).isZero();
    }

    @Test
    void getApiSocialProfileMeReturns400WhenJwtDoesNotContainUserIdClaim() throws Exception {
        mockMvc.perform(get("/api/social/profile/me")
                        .with(jwt()))
                .andExpect(status().isBadRequest());
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
}
