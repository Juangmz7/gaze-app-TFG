package com.app.socialservice.user.infrastructure.controller;

import java.util.Map;
import java.util.UUID;

import com.app.socialservice.TestcontainersConfiguration;
import com.app.socialservice.shared.infrastructure.mapper.JsonMapper;
import com.app.socialservice.shared.infrastructure.repository.OutboxEventRepository;
import com.app.socialservice.shared.testutil.SocialIntegrationSeeder;
import com.app.socialservice.user.domain.enums.UserAccountStatus;
import com.app.socialservice.user.infrastructure.repository.JpaUserRepository;
import com.app.socialservice.user.testutil.UserMother;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The profile write and its UserUpdatedEvent outbox row share one transaction: when the outbox
 * row cannot be written, the profile change is rolled back too.
 */
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class UserProfileUpdateTransactionIntegrationTest {

    private static final String OLD_PICTURE = "https://cdn.example.com/old.png";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JpaUserRepository jpaUserRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private JsonMapper jsonMapper;

    @BeforeEach
    void setUp() {
        outboxEventRepository.deleteAll();
        jpaUserRepository.deleteAll();
    }

    @Test
    void putApiSocialProfileRollsBackTheProfileWriteWhenTheOutboxRowCannotBeSaved() throws Exception {
        var userId = UUID.randomUUID();
        SocialIntegrationSeeder.seedUser(jpaUserRepository, UserMother.entity(
                userId, "rollback-user", UserAccountStatus.ACCEPTED, null, "Old bio", Map.of(), OLD_PICTURE));
        var versionBefore = jpaUserRepository.findById(userId).orElseThrow().getVersion();

        when(jsonMapper.toJson(any())).thenThrow(new RuntimeException("serialization failed"));

        mockMvc.perform(put("/api/social/profile")
                        .with(jwt().jwt(jwt -> jwt.subject(userId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "description": "New bio",
                                  "profilePicture": "https://cdn.example.com/new.png"
                                }
                                """))
                .andExpect(status().isInternalServerError());

        var userAfter = jpaUserRepository.findById(userId).orElseThrow();
        assertThat(userAfter.getPictureUrl()).isEqualTo(OLD_PICTURE);
        assertThat(userAfter.getBio().getDescription()).isEqualTo("Old bio");
        assertThat(userAfter.getVersion()).isEqualTo(versionBefore);
        assertThat(outboxEventRepository.count()).isZero();
    }
}
