package com.app.socialservice.shared.infrastructure.rabbitmq.listener;

import java.time.Instant;
import java.util.UUID;

import com.app.socialservice.TestcontainersConfiguration;
import com.app.socialservice.follow.infrastructure.entity.FollowEntity;
import com.app.socialservice.follow.infrastructure.entity.FollowEntityId;
import com.app.socialservice.follow.infrastructure.enums.FollowStatus;
import com.app.socialservice.follow.infrastructure.events.UserFollowedEvent;
import com.app.socialservice.follow.infrastructure.events.UserUnfollowedEvent;
import com.app.socialservice.follow.infrastructure.repository.JpaFollowRepository;
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
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class FollowRabbitMQListenerIntegrationTest {

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @jakarta.annotation.Resource
    private FollowRabbitMQListener followRabbitMQListener;

    @jakarta.annotation.Resource
    private JpaUserRepository jpaUserRepository;

    @jakarta.annotation.Resource
    private UserNodeRepository userNodeRepository;

    @jakarta.annotation.Resource
    private Neo4jClient neo4jClient;

    @jakarta.annotation.Resource
    private JpaFollowRepository jpaFollowRepository;

    @jakarta.annotation.Resource
    private ProcessedEventsRepository processedEventsRepository;

    @BeforeEach
    void setUp() {
        processedEventsRepository.deleteAll();
        jpaFollowRepository.deleteAll();
        neo4jClient.query("MATCH ()-[r]->() DELETE r").run();
        neo4jClient.query("MATCH (n) DELETE n").run();
        userNodeRepository.deleteAll();
        jpaUserRepository.deleteAll();
    }

    @Test
    void followRabbitMqListenerProcessesUserFollowedEventsAndSavesThemToProcessedEventsRepository() {
        var followerId = UUID.randomUUID();
        var followedId = UUID.randomUUID();
        seedAcceptedUser(followerId, "follow-listener-follower", "follow-listener-follower@example.com");
        seedAcceptedUser(followedId, "follow-listener-followed", "follow-listener-followed@example.com");
        userNodeRepository.save(com.app.socialservice.user.infrastructure.entity.UserNode.builder().id(followerId).build());
        userNodeRepository.save(com.app.socialservice.user.infrastructure.entity.UserNode.builder().id(followedId).build());
        jpaFollowRepository.save(new FollowEntity(
                new FollowEntityId(followerId, followedId),
                FollowStatus.ACTIVE,
                Instant.now(),
                null
        ));

        var event = UserFollowedEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(UUID.randomUUID())
                .occurredAt(Instant.now())
                .followerUserId(followerId)
                .followedUserId(followedId)
                .build();

        followRabbitMQListener.onUserFollowed(event);

        assertThat(processedEventsRepository.findById(event.id())).isPresent();
        assertThat(countFollowRelationships(followerId, followedId)).isEqualTo(1L);
    }

    @Test
    void followRabbitMqListenerCorrectlyIgnoresDuplicateUserFollowedEventsUsingProcessedEventsRepository() {
        var followerId = UUID.randomUUID();
        var followedId = UUID.randomUUID();
        seedAcceptedUser(followerId, "duplicate-follow-follower", "duplicate-follow-follower@example.com");
        seedAcceptedUser(followedId, "duplicate-follow-followed", "duplicate-follow-followed@example.com");
        userNodeRepository.save(com.app.socialservice.user.infrastructure.entity.UserNode.builder().id(followerId).build());
        userNodeRepository.save(com.app.socialservice.user.infrastructure.entity.UserNode.builder().id(followedId).build());
        jpaFollowRepository.save(new FollowEntity(
                new FollowEntityId(followerId, followedId),
                FollowStatus.ACTIVE,
                Instant.now(),
                null
        ));

        var event = UserFollowedEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(UUID.randomUUID())
                .occurredAt(Instant.now())
                .followerUserId(followerId)
                .followedUserId(followedId)
                .build();

        followRabbitMQListener.onUserFollowed(event);
        followRabbitMQListener.onUserFollowed(event);

        assertThat(processedEventsRepository.findById(event.id())).isPresent();
        assertThat(countFollowRelationships(followerId, followedId)).isEqualTo(1L);
    }

    @Test
    void followRabbitMqListenerRetriesUserFollowedEventsWhenNeo4jUserNodesArriveAfterTheFollowEvent() {
        var followerId = UUID.randomUUID();
        var followedId = UUID.randomUUID();
        seedAcceptedUser(followerId, "delayed-follow-follower", "delayed-follow-follower@example.com");
        seedAcceptedUser(followedId, "delayed-follow-followed", "delayed-follow-followed@example.com");
        jpaFollowRepository.save(new FollowEntity(
                new FollowEntityId(followerId, followedId),
                FollowStatus.ACTIVE,
                Instant.now(),
                null
        ));

        var event = UserFollowedEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(UUID.randomUUID())
                .occurredAt(Instant.now())
                .followerUserId(followerId)
                .followedUserId(followedId)
                .build();

        assertThatThrownBy(() -> followRabbitMQListener.onUserFollowed(event))
                .isInstanceOf(InvalidDataAccessApiUsageException.class)
                .hasMessageContaining("Neo4j user nodes are missing");

        assertThat(processedEventsRepository.findById(event.id())).isEmpty();
        assertThat(countFollowRelationships(followerId, followedId)).isZero();

        userNodeRepository.save(com.app.socialservice.user.infrastructure.entity.UserNode.builder().id(followerId).build());
        userNodeRepository.save(com.app.socialservice.user.infrastructure.entity.UserNode.builder().id(followedId).build());

        followRabbitMQListener.onUserFollowed(event);

        assertThat(processedEventsRepository.findById(event.id())).isPresent();
        assertThat(countFollowRelationships(followerId, followedId)).isEqualTo(1L);
    }

    @Test
    void followRabbitMqListenerProcessesUserUnfollowedEventsAndDeletesTheNeo4jRelationship() {
        var followerId = UUID.randomUUID();
        var followedId = UUID.randomUUID();
        seedAcceptedUser(followerId, "unfollow-listener-follower", "unfollow-listener-follower@example.com");
        seedAcceptedUser(followedId, "unfollow-listener-followed", "unfollow-listener-followed@example.com");
        userNodeRepository.save(com.app.socialservice.user.infrastructure.entity.UserNode.builder().id(followerId).build());
        userNodeRepository.save(com.app.socialservice.user.infrastructure.entity.UserNode.builder().id(followedId).build());
        jpaFollowRepository.save(new FollowEntity(
                new FollowEntityId(followerId, followedId),
                FollowStatus.REMOVED,
                Instant.now().minusSeconds(60),
                Instant.now()
        ));
        neo4jClient.query("""
                MATCH (follower:User {id: $followerId})
                MATCH (followed:User {id: $followedId})
                MERGE (follower)-[:FOLLOWS]->(followed)
                """)
                .bind(followerId.toString()).to("followerId")
                .bind(followedId.toString()).to("followedId")
                .run();

        var event = UserUnfollowedEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(UUID.randomUUID())
                .occurredAt(Instant.now())
                .followerUserId(followerId)
                .followedUserId(followedId)
                .build();

        followRabbitMQListener.onUserUnfollowed(event);

        assertThat(processedEventsRepository.findById(event.id())).isPresent();
        assertThat(countFollowRelationships(followerId, followedId)).isZero();
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
