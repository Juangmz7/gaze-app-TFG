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
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class UserProfileControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JpaUserRepository jpaUserRepository;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void setUp() {
        jpaUserRepository.deleteAll();
    }

    @Test
    void putApiSocialProfileUpdatesAuthenticatedUsersOwnProfile() throws Exception {
        var authenticatedUserId = UUID.randomUUID();
        var otherUserId = UUID.randomUUID();

        seedUser(authenticatedUserId, "profile-owner", "Owner bio", "https://cdn.example.com/owner-old.png");
        seedUser(otherUserId, "other-user", "Other bio", "https://cdn.example.com/other.png");

        mockMvc.perform(put("/api/social/profile")
                        .with(jwt().jwt(jwt -> jwt.claim("userId", authenticatedUserId.toString())))
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
                        .with(jwt().jwt(jwt -> jwt.claim("userId", authenticatedUserId.toString())))
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
                        .with(jwt().jwt(jwt -> jwt.claim("userId", authenticatedUserId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "description": "%s"
                                }
                                """.formatted("a".repeat(256))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
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
                        .with(jwt().jwt(jwt -> jwt.claim("userId", authenticatedUserId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "profilePicture": "%s"
                                }
                                """.formatted("https://%s".formatted("a".repeat(248)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
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
                        .with(jwt().jwt(jwt -> jwt.claim("userId", authenticatedUserId.toString())))
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
