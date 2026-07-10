package com.app.socialservice.block.infrastructure.controller;

import java.time.Instant;
import java.util.UUID;

import com.app.socialservice.TestcontainersConfiguration;
import com.app.socialservice.block.infrastructure.entity.BlockEntity;
import com.app.socialservice.block.infrastructure.entity.BlockEntityId;
import com.app.socialservice.block.infrastructure.request.BlockUserRequest;
import com.app.socialservice.block.infrastructure.repository.JpaBlockRepository;
import com.app.socialservice.follow.infrastructure.entity.FollowEntity;
import com.app.socialservice.follow.infrastructure.entity.FollowEntityId;
import com.app.socialservice.follow.infrastructure.enums.FollowStatus;
import com.app.socialservice.follow.infrastructure.repository.JpaFollowRepository;
import com.app.socialservice.shared.infrastructure.mapper.JsonMapper;
import com.app.socialservice.shared.infrastructure.repository.OutboxEventRepository;
import com.app.socialservice.user.domain.enums.UserAccountStatus;
import com.app.socialservice.user.infrastructure.entity.UserEntity;
import com.app.socialservice.user.infrastructure.repository.JpaUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class BlockUnblockTransactionIntegrationTest {

    @jakarta.annotation.Resource
    private MockMvc mockMvc;

    @jakarta.annotation.Resource
    private JpaUserRepository jpaUserRepository;

    @jakarta.annotation.Resource
    private JpaBlockRepository jpaBlockRepository;

    @jakarta.annotation.Resource
    private JpaFollowRepository jpaFollowRepository;

    @jakarta.annotation.Resource
    private OutboxEventRepository outboxEventRepository;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private JsonMapper jsonMapper;

    @BeforeEach
    void setUp() {
        outboxEventRepository.deleteAll();
        jpaBlockRepository.deleteAll();
        jpaFollowRepository.deleteAll();
        jpaUserRepository.deleteAll();
    }

    @Test
    void deleteApiSocialBlockRollsBackBlockDeleteAndFollowStatusUpdateWhenOutboxSerializationFails() throws Exception {
        var unblockerId = UUID.randomUUID();
        var unblockedId = UUID.randomUUID();

        seedUser(unblockerId, "rollback-unblocker");
        seedUser(unblockedId, "rollback-unblocked");
        seedBlockedState(unblockerId, unblockedId);

        when(jsonMapper.toJson(any())).thenThrow(new RuntimeException("serialization failed"));

        mockMvc.perform(delete("/api/social/block")
                        .with(jwt().jwt(jwt -> jwt.subject(unblockerId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"blockedUserId":"%s"}
                                """.formatted(unblockedId)))
                .andExpect(status().isInternalServerError());

        assertThat(jpaBlockRepository.findById(new BlockEntityId(unblockerId, unblockedId))).isPresent();
        assertThat(jpaFollowRepository.findById(new FollowEntityId(unblockerId, unblockedId))).get()
                .extracting(FollowEntity::getStatus)
                .isEqualTo(FollowStatus.BLOCKED);
        assertThat(jpaFollowRepository.findById(new FollowEntityId(unblockedId, unblockerId))).get()
                .extracting(FollowEntity::getStatus)
                .isEqualTo(FollowStatus.BLOCKED);
        assertThat(outboxEventRepository.count()).isZero();
    }

    private void seedUser(UUID userId, String username) {
        jpaUserRepository.save(UserEntity.builder()
                .id(userId)
                .username(username)
                .email(username + "@example.com")
                .accountStatus(UserAccountStatus.ACCEPTED)
                .build());
    }

    private void seedBlockedState(UUID firstUserId, UUID secondUserId) {
        jpaBlockRepository.save(new BlockEntity(
                new BlockEntityId(firstUserId, secondUserId),
                Instant.now().minusSeconds(120)
        ));
        jpaFollowRepository.save(new FollowEntity(
                new FollowEntityId(firstUserId, secondUserId),
                FollowStatus.BLOCKED,
                Instant.now().minusSeconds(300),
                Instant.now().minusSeconds(60)
        ));
        jpaFollowRepository.save(new FollowEntity(
                new FollowEntityId(secondUserId, firstUserId),
                FollowStatus.BLOCKED,
                Instant.now().minusSeconds(300),
                Instant.now().minusSeconds(60)
        ));
    }
}
