package com.app.socialservice.user.infrastructure.controller;

import java.sql.Timestamp;
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
import com.app.socialservice.user.infrastructure.entity.UserNode;
import com.app.socialservice.user.infrastructure.repository.JpaUserRepository;
import com.app.socialservice.user.infrastructure.repository.UserNodeRepository;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class RecommendedUserControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JpaUserRepository jpaUserRepository;

    @Autowired
    private JpaBlockRepository jpaBlockRepository;

    @Autowired
    private JpaFollowRepository jpaFollowRepository;

    @Autowired
    private UserNodeRepository userNodeRepository;

    @Autowired
    private Neo4jClient neo4jClient;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void setUp() {
        jpaBlockRepository.deleteAll();
        jpaFollowRepository.deleteAll();
        jpaUserRepository.deleteAll();

        neo4jClient.query("MATCH ()-[r]->() DELETE r").run();
        neo4jClient.query("MATCH (n) DELETE n").run();
    }

    @Test
    void getRecommendedUsersReturnsOrderedProfilesExcludingBlockedAndBannedUsers() throws Exception {
        var requesterId = UUID.randomUUID();
        var mutualOneId = UUID.randomUUID();
        var mutualTwoId = UUID.randomUUID();
        var highRankCandidateId = UUID.randomUUID();
        var newerTieCandidateId = UUID.randomUUID();
        var olderTieCandidateId = UUID.randomUUID();
        var blockedCandidateId = UUID.randomUUID();
        var bannedCandidateId = UUID.randomUUID();
        var alreadyFollowedCandidateId = UUID.randomUUID();

        seedUser(requesterId, "requester", UserAccountStatus.ACCEPTED, Instant.parse("2026-01-01T00:00:00Z"),
                "Requester", "https://cdn.example/requester.png");
        seedUser(mutualOneId, "mutual-one", UserAccountStatus.ACCEPTED, Instant.parse("2026-01-02T00:00:00Z"),
                null, null);
        seedUser(mutualTwoId, "mutual-two", UserAccountStatus.ACCEPTED, Instant.parse("2026-01-03T00:00:00Z"),
                null, null);
        seedUser(highRankCandidateId, "high-rank", UserAccountStatus.ACCEPTED, Instant.parse("2026-02-01T00:00:00Z"),
                "High rank description", "https://cdn.example/high-rank.png");
        seedUser(newerTieCandidateId, "newer-tie", UserAccountStatus.ACCEPTED, Instant.parse("2026-05-01T00:00:00Z"),
                "Newer tie description", "https://cdn.example/newer-tie.png");
        seedUser(olderTieCandidateId, "older-tie", UserAccountStatus.ACCEPTED, Instant.parse("2026-03-01T00:00:00Z"),
                "Older tie description", "https://cdn.example/older-tie.png");
        seedUser(blockedCandidateId, "blocked-candidate", UserAccountStatus.ACCEPTED,
                Instant.parse("2026-04-01T00:00:00Z"), "Blocked description", "https://cdn.example/blocked.png");
        seedUser(bannedCandidateId, "banned-candidate", UserAccountStatus.BANNED,
                Instant.parse("2026-06-01T00:00:00Z"), "Banned description", "https://cdn.example/banned.png");
        seedUser(alreadyFollowedCandidateId, "already-followed", UserAccountStatus.ACCEPTED,
                Instant.parse("2026-04-15T00:00:00Z"), "Already followed description",
                "https://cdn.example/already-followed.png");

        seedNode(requesterId);
        seedNode(mutualOneId);
        seedNode(mutualTwoId);
        seedNode(highRankCandidateId);
        seedNode(newerTieCandidateId);
        seedNode(olderTieCandidateId);
        seedNode(blockedCandidateId);
        seedNode(bannedCandidateId);
        seedNode(alreadyFollowedCandidateId);

        seedGraphFollow(requesterId, mutualOneId);
        seedGraphFollow(requesterId, mutualTwoId);
        seedGraphFollow(mutualOneId, highRankCandidateId);
        seedGraphFollow(mutualTwoId, highRankCandidateId);
        seedGraphFollow(mutualOneId, newerTieCandidateId);
        seedGraphFollow(mutualTwoId, olderTieCandidateId);
        seedGraphFollow(mutualOneId, blockedCandidateId);
        seedGraphFollow(mutualOneId, bannedCandidateId);
        seedGraphFollow(mutualOneId, alreadyFollowedCandidateId);
        seedGraphFollow(requesterId, alreadyFollowedCandidateId);

        seedBlock(requesterId, blockedCandidateId);
        seedActiveFollow(newerTieCandidateId, requesterId);

        mockMvc.perform(get("/api/social/recommended-users")
                        .with(jwt().jwt(jwt -> jwt.claim("userId", requesterId.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].username").value("high-rank"))
                .andExpect(jsonPath("$[0].description").value("High rank description"))
                .andExpect(jsonPath("$[0].profilePic").value("https://cdn.example/high-rank.png"))
                .andExpect(jsonPath("$[0].followsYou").value(false))
                .andExpect(jsonPath("$[1].username").value("newer-tie"))
                .andExpect(jsonPath("$[1].followsYou").value(true))
                .andExpect(jsonPath("$[2].username").value("older-tie"))
                .andExpect(jsonPath("$[*].username", Matchers.not(Matchers.hasItem("blocked-candidate"))))
                .andExpect(jsonPath("$[*].username", Matchers.not(Matchers.hasItem("banned-candidate"))))
                .andExpect(jsonPath("$[*].username", Matchers.not(Matchers.hasItem("already-followed"))));

        assertThat(jpaBlockRepository.findById(new BlockEntityId(requesterId, blockedCandidateId))).isPresent();
        assertThat(jpaFollowRepository.findById(new FollowEntityId(newerTieCandidateId, requesterId))).isPresent();
    }

    @Test
    void getRecommendedUsersReturnsAtMost20Users() throws Exception {
        var requesterId = UUID.randomUUID();
        seedUser(requesterId, "limit-requester", UserAccountStatus.ACCEPTED, Instant.parse("2026-01-01T00:00:00Z"),
                null, null);
        seedNode(requesterId);

        for (int index = 0; index < 25; index++) {
            var mutualId = UUID.randomUUID();
            var candidateId = UUID.randomUUID();
            seedUser(mutualId, "limit-mutual-" + index, UserAccountStatus.ACCEPTED,
                    Instant.parse("2026-01-02T00:00:00Z").plusSeconds(index), null, null);
            seedUser(candidateId, "limit-candidate-" + index, UserAccountStatus.ACCEPTED,
                    Instant.parse("2026-02-01T00:00:00Z").plusSeconds(index), "Candidate " + index,
                    "https://cdn.example/limit-" + index + ".png");
            seedNode(mutualId);
            seedNode(candidateId);
            seedGraphFollow(requesterId, mutualId);
            seedGraphFollow(mutualId, candidateId);
        }

        mockMvc.perform(get("/api/social/recommended-users")
                        .with(jwt().jwt(jwt -> jwt.claim("userId", requesterId.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(20));
    }

    @Test
    void getRecommendedUsersReturns404WhenRequesterDoesNotExist() throws Exception {
        var missingRequesterId = UUID.randomUUID();

        mockMvc.perform(get("/api/social/recommended-users")
                        .with(jwt().jwt(jwt -> jwt.claim("userId", missingRequesterId.toString()))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("User not found: " + missingRequesterId))
                .andExpect(jsonPath("$.path").value("/api/social/recommended-users"));
    }

    private void seedUser(
            UUID userId,
            String username,
            UserAccountStatus accountStatus,
            Instant createdAt,
            String description,
            String profilePic
    ) {
        jpaUserRepository.save(UserEntity.builder()
                .id(userId)
                .username(username)
                .email(username + "@example.com")
                .pictureUrl(profilePic)
                .bio(UserBioEmbeddable.builder()
                        .description(description)
                        .socialMedia(Map.of())
                        .build())
                .accountStatus(accountStatus)
                .createdAt(createdAt)
                .updatedAt(createdAt)
                .build());

        jdbcTemplate.update(
                "UPDATE users SET created_at = ?, updated_at = ? WHERE id = ?",
                Timestamp.from(createdAt),
                Timestamp.from(createdAt),
                userId
        );
    }

    private void seedNode(UUID userId) {
        userNodeRepository.save(UserNode.builder().id(userId).build());
    }

    private void seedBlock(UUID blockerId, UUID blockedId) {
        jpaBlockRepository.save(new BlockEntity(new BlockEntityId(blockerId, blockedId), Instant.now()));
    }

    private void seedActiveFollow(UUID followerId, UUID followedId) {
        jpaFollowRepository.save(new FollowEntity(
                new FollowEntityId(followerId, followedId),
                FollowStatus.ACTIVE,
                Instant.now(),
                Instant.now()
        ));
    }

    private void seedGraphFollow(UUID followerId, UUID followedId) {
        neo4jClient.query("""
                MERGE (follower:User {id: $followerId})
                MERGE (followed:User {id: $followedId})
                MERGE (follower)-[:FOLLOWS]->(followed)
                """)
                .bind(followerId.toString()).to("followerId")
                .bind(followedId.toString()).to("followedId")
                .run();
    }
}
