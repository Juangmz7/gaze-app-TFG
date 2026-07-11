package com.app.socialservice.shared.infrastructure.rabbitmq.listener;

import java.time.Instant;
import java.util.UUID;

import com.app.socialservice.TestcontainersConfiguration;
import com.app.socialservice.block.infrastructure.events.UserBlockedEvent;
import com.app.socialservice.shared.infrastructure.entity.TargetDatabase;
import com.app.socialservice.shared.infrastructure.repository.ProcessedEventsRepository;
import com.app.socialservice.user.domain.enums.UserAccountStatus;
import com.app.socialservice.user.infrastructure.entity.UserEntity;
import com.app.socialservice.user.infrastructure.repository.JpaUserRepository;
import com.app.socialservice.user.infrastructure.repository.UserNodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class BlockRabbitMQListenerIntegrationTest {

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @jakarta.annotation.Resource
    private BlockRabbitMQListener blockRabbitMQListener;

    @jakarta.annotation.Resource
    private JpaUserRepository jpaUserRepository;

    @jakarta.annotation.Resource
    private UserNodeRepository userNodeRepository;

    @jakarta.annotation.Resource
    private Neo4jClient neo4jClient;

    @jakarta.annotation.Resource
    private ProcessedEventsRepository processedEventsRepository;

    @BeforeEach
    void setUp() {
        processedEventsRepository.deleteAll();
        neo4jClient.query("MATCH ()-[r]->() DELETE r").run();
        neo4jClient.query("MATCH (n) DELETE n").run();
        userNodeRepository.deleteAll();
        jpaUserRepository.deleteAll();
    }

    @Test
    void blockRabbitMqListenerProcessesUserBlockedEventsAndSavesThemToProcessedEventsRepository() {
        var blockerId = UUID.randomUUID();
        var blockedId = UUID.randomUUID();
        seedAcceptedUser(blockerId, "block-listener-blocker", "block-listener-blocker@example.com");
        seedAcceptedUser(blockedId, "block-listener-blocked", "block-listener-blocked@example.com");
        userNodeRepository.save(com.app.socialservice.user.infrastructure.entity.UserNode.builder().id(blockerId).build());
        userNodeRepository.save(com.app.socialservice.user.infrastructure.entity.UserNode.builder().id(blockedId).build());
        
        // Seed bidirectional follow relationship in Neo4j
        neo4jClient.query("""
                MATCH (blocker:User {id: $blockerId}), (blocked:User {id: $blockedId})
                MERGE (blocker)-[:FOLLOWS]->(blocked)
                MERGE (blocked)-[:FOLLOWS]->(blocker)
                """)
                .bind(blockerId.toString()).to("blockerId")
                .bind(blockedId.toString()).to("blockedId")
                .run();

        assertThat(countFollowRelationships(blockerId, blockedId)).isEqualTo(1L);
        assertThat(countFollowRelationships(blockedId, blockerId)).isEqualTo(1L);

        var event = new UserBlockedEvent(
                UUID.randomUUID(),
                UUID.randomUUID(),
                Instant.now(),
                blockerId,
                blockedId
        );

        blockRabbitMQListener.onUserBlocked(event);

        assertThat(processedEventsRepository.findByIdAndTargetDatabase(event.id(), TargetDatabase.NEO4J)).isPresent();
        assertThat(countFollowRelationships(blockerId, blockedId)).isZero();
        assertThat(countFollowRelationships(blockedId, blockerId)).isZero();
    }

    @Test
    void blockRabbitMqListenerCorrectlyIgnoresDuplicateUserBlockedEventsUsingProcessedEventsRepository() {
        var blockerId = UUID.randomUUID();
        var blockedId = UUID.randomUUID();
        seedAcceptedUser(blockerId, "duplicate-block-blocker", "duplicate-block-blocker@example.com");
        seedAcceptedUser(blockedId, "duplicate-block-blocked", "duplicate-block-blocked@example.com");
        userNodeRepository.save(com.app.socialservice.user.infrastructure.entity.UserNode.builder().id(blockerId).build());
        userNodeRepository.save(com.app.socialservice.user.infrastructure.entity.UserNode.builder().id(blockedId).build());
        
        // Seed bidirectional follow relationship in Neo4j
        neo4jClient.query("""
                MATCH (blocker:User {id: $blockerId}), (blocked:User {id: $blockedId})
                MERGE (blocker)-[:FOLLOWS]->(blocked)
                MERGE (blocked)-[:FOLLOWS]->(blocker)
                """)
                .bind(blockerId.toString()).to("blockerId")
                .bind(blockedId.toString()).to("blockedId")
                .run();

        var event = new UserBlockedEvent(
                UUID.randomUUID(),
                UUID.randomUUID(),
                Instant.now(),
                blockerId,
                blockedId
        );

        blockRabbitMQListener.onUserBlocked(event);
        
        // Try to run again
        blockRabbitMQListener.onUserBlocked(event);

        assertThat(processedEventsRepository.findByIdAndTargetDatabase(event.id(), TargetDatabase.NEO4J)).isPresent();
        assertThat(countFollowRelationships(blockerId, blockedId)).isZero();
        assertThat(countFollowRelationships(blockedId, blockerId)).isZero();
    }

    private void seedAcceptedUser(UUID userId, String username, String email) {
        jpaUserRepository.save(UserEntity.builder()
                .id(userId)
                .username(username)
                .email(email)
                .accountStatus(UserAccountStatus.ACCEPTED)
                .build());
    }

    private long countFollowRelationships(UUID followerId, UUID followedId) {
        var result = neo4jClient.query("""
                MATCH (follower:User)-[follow:FOLLOWS]->(followed:User)
                WHERE follower.id = $followerId AND followed.id = $followedId
                RETURN count(follow) AS relationships
                """)
                .bind(followerId.toString()).to("followerId")
                .bind(followedId.toString()).to("followedId")
                .fetch()
                .one();

        if (result.isEmpty()) {
            return 0L;
        }

        return ((Number) result.get().get("relationships")).longValue();
    }
}
