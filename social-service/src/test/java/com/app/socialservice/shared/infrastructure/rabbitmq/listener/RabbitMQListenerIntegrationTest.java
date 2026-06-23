package com.app.socialservice.shared.infrastructure.rabbitmq.listener;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import com.app.socialservice.TestcontainersConfiguration;
import com.app.socialservice.follow.infrastructure.entity.FollowEntity;
import com.app.socialservice.follow.infrastructure.entity.FollowEntityId;
import com.app.socialservice.follow.infrastructure.enums.FollowStatus;
import com.app.socialservice.follow.infrastructure.events.UserFollowedEvent;
import com.app.socialservice.follow.infrastructure.repository.JpaFollowRepository;
import com.app.socialservice.shared.infrastructure.repository.OutboxEventRepository;
import com.app.socialservice.shared.infrastructure.repository.ProcessedEventsRepository;
import com.app.socialservice.user.domain.enums.UserAccountStatus;
import com.app.socialservice.user.infrastructure.entity.UserEntity;
import com.app.socialservice.user.infrastructure.events.UserDeletedFromAuthEvent;
import com.app.socialservice.user.infrastructure.events.UserInfoFromAuthUpdatedEvent;
import com.app.socialservice.user.infrastructure.events.UserRegisteredFromAuthEvent;
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
class RabbitMQListenerIntegrationTest {

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @jakarta.annotation.Resource
    private RabbitMQListener rabbitMQListener;

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

    @jakarta.annotation.Resource
    private OutboxEventRepository outboxEventRepository;

    @BeforeEach
    void setUp() {
        processedEventsRepository.deleteAll();
        outboxEventRepository.deleteAll();
        jpaFollowRepository.deleteAll();
        neo4jClient.query("MATCH ()-[r]->() DELETE r").run();
        neo4jClient.query("MATCH (n) DELETE n").run();
        userNodeRepository.deleteAll();
        jpaUserRepository.deleteAll();
    }

    @Test
    void rabbitMqListenerProcessesUserEventsAndSavesThemToProcessedEventsRepository() {
        var event = userRegisteredFromAuthEvent();
        var expectedEventId = deterministicUuid(
                "auth-register-event",
                event.type(),
                event.userId(),
                String.valueOf(event.time()),
                event.details().username(),
                event.details().email()
        );

        rabbitMQListener.onUserRegisteredFromAuth(event);

        assertThat(jpaUserRepository.findById(UUID.fromString(event.userId()))).isPresent();
        assertThat(processedEventsRepository.findById(expectedEventId)).isPresent();
        assertThat(outboxEventRepository.findAll()).hasSize(1);
    }

    @Test
    void rabbitMqListenerCorrectlyIgnoresDuplicateEventsUsingProcessedEventsRepository() {
        var event = userRegisteredFromAuthEvent();
        var expectedEventId = deterministicUuid(
                "auth-register-event",
                event.type(),
                event.userId(),
                String.valueOf(event.time()),
                event.details().username(),
                event.details().email()
        );

        rabbitMQListener.onUserRegisteredFromAuth(event);

        var outboxCountAfterFirstMessage = outboxEventRepository.count();

        rabbitMQListener.onUserRegisteredFromAuth(event);

        assertThat(jpaUserRepository.count()).isEqualTo(1);
        assertThat(outboxEventRepository.count()).isEqualTo(outboxCountAfterFirstMessage);
        assertThat(processedEventsRepository.findById(expectedEventId)).isPresent();
    }

    @Test
    void rabbitMqListenerProcessesUserAuthUpdateEventsAndSavesThemToProcessedEventsRepository() {
        var userId = UUID.randomUUID();
        seedAcceptedUser(userId, "before-update", "before-update@example.com");
        var event = userInfoFromAuthUpdatedEvent(userId, "after-update", "after-update@example.com");
        var expectedEventId = deterministicUuid(
                "auth-update-event",
                event.type(),
                event.userId(),
                String.valueOf(event.time()),
                event.details().username(),
                event.details().email()
        );

        rabbitMQListener.onUserInfoFromAuthUpdated(event);

        assertThat(jpaUserRepository.findById(userId)).get()
                .extracting(UserEntity::getUsername, UserEntity::getEmail)
                .containsExactly("after-update", "after-update@example.com");
        assertThat(processedEventsRepository.findById(expectedEventId)).isPresent();
        assertThat(outboxEventRepository.findAll()).hasSize(1);
    }

    @Test
    void rabbitMqListenerCorrectlyIgnoresDuplicateUserAuthUpdateEventsUsingProcessedEventsRepository() {
        var userId = UUID.randomUUID();
        seedAcceptedUser(userId, "before-duplicate-update", "before-duplicate-update@example.com");
        var event = userInfoFromAuthUpdatedEvent(userId, "after-duplicate-update", "after-duplicate-update@example.com");
        var expectedEventId = deterministicUuid(
                "auth-update-event",
                event.type(),
                event.userId(),
                String.valueOf(event.time()),
                event.details().username(),
                event.details().email()
        );

        rabbitMQListener.onUserInfoFromAuthUpdated(event);

        var outboxCountAfterFirstMessage = outboxEventRepository.count();

        rabbitMQListener.onUserInfoFromAuthUpdated(event);

        assertThat(jpaUserRepository.findById(userId)).get()
                .extracting(UserEntity::getUsername, UserEntity::getEmail)
                .containsExactly("after-duplicate-update", "after-duplicate-update@example.com");
        assertThat(outboxEventRepository.count()).isEqualTo(outboxCountAfterFirstMessage);
        assertThat(processedEventsRepository.findById(expectedEventId)).isPresent();
    }

    @Test
    void rabbitMqListenerProcessesUserDeleteEventsAndSavesThemToProcessedEventsRepository() {
        var userId = UUID.randomUUID();
        seedAcceptedUser(userId, "before-delete", "before-delete@example.com");
        var event = userDeletedFromAuthEvent(userId);
        var expectedEventId = deterministicUuid(
                "auth-delete-event",
                event.type(),
                event.userId(),
                String.valueOf(event.time())
        );

        rabbitMQListener.onUserDeletedFromAuth(event);

        assertThat(jpaUserRepository.findById(userId)).get()
                .extracting(UserEntity::getAccountStatus)
                .isEqualTo(UserAccountStatus.DELETED);
        assertThat(processedEventsRepository.findById(expectedEventId)).isPresent();
        assertThat(outboxEventRepository.findAll()).hasSize(1);
    }

    @Test
    void rabbitMqListenerCorrectlyIgnoresDuplicateUserDeleteEventsUsingProcessedEventsRepository() {
        var userId = UUID.randomUUID();
        seedAcceptedUser(userId, "before-duplicate-delete", "before-duplicate-delete@example.com");
        var event = userDeletedFromAuthEvent(userId);
        var expectedEventId = deterministicUuid(
                "auth-delete-event",
                event.type(),
                event.userId(),
                String.valueOf(event.time())
        );

        rabbitMQListener.onUserDeletedFromAuth(event);

        var outboxCountAfterFirstMessage = outboxEventRepository.count();

        rabbitMQListener.onUserDeletedFromAuth(event);

        assertThat(jpaUserRepository.findById(userId)).get()
                .extracting(UserEntity::getAccountStatus)
                .isEqualTo(UserAccountStatus.DELETED);
        assertThat(outboxEventRepository.count()).isEqualTo(outboxCountAfterFirstMessage);
        assertThat(processedEventsRepository.findById(expectedEventId)).isPresent();
    }

    @Test
    void rabbitMqListenerProcessesUserFollowedEventsAndSavesThemToProcessedEventsRepository() {
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

        rabbitMQListener.onUserFollowed(event);

        assertThat(processedEventsRepository.findById(event.id())).isPresent();
        assertThat(countFollowRelationships(followerId, followedId)).isEqualTo(1L);
    }

    @Test
    void rabbitMqListenerCorrectlyIgnoresDuplicateUserFollowedEventsUsingProcessedEventsRepository() {
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

        rabbitMQListener.onUserFollowed(event);
        rabbitMQListener.onUserFollowed(event);

        assertThat(processedEventsRepository.findById(event.id())).isPresent();
        assertThat(countFollowRelationships(followerId, followedId)).isEqualTo(1L);
    }

    @Test
    void rabbitMqListenerRetriesUserFollowedEventsWhenNeo4jUserNodesArriveAfterTheFollowEvent() {
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

        assertThatThrownBy(() -> rabbitMQListener.onUserFollowed(event))
                .isInstanceOf(InvalidDataAccessApiUsageException.class)
                .hasMessageContaining("Neo4j user nodes are missing");

        assertThat(processedEventsRepository.findById(event.id())).isEmpty();
        assertThat(countFollowRelationships(followerId, followedId)).isZero();

        userNodeRepository.save(com.app.socialservice.user.infrastructure.entity.UserNode.builder().id(followerId).build());
        userNodeRepository.save(com.app.socialservice.user.infrastructure.entity.UserNode.builder().id(followedId).build());

        rabbitMQListener.onUserFollowed(event);

        assertThat(processedEventsRepository.findById(event.id())).isPresent();
        assertThat(countFollowRelationships(followerId, followedId)).isEqualTo(1L);
    }

    private UserRegisteredFromAuthEvent userRegisteredFromAuthEvent() {
        return new UserRegisteredFromAuthEvent(
                1_717_171_717_000L,
                "REGISTER",
                "social",
                "auth-service",
                UUID.randomUUID().toString(),
                "session",
                "127.0.0.1",
                null,
                new UserRegisteredFromAuthEvent.Details(
                        "pwd",
                        "code",
                        "self",
                        "http://localhost",
                        "Test",
                        "User",
                        "listener-it@example.com",
                        "listener-it"
                )
        );
    }

    private UserInfoFromAuthUpdatedEvent userInfoFromAuthUpdatedEvent(UUID userId, String username, String email) {
        return new UserInfoFromAuthUpdatedEvent(
                1_717_171_818_000L,
                "UPDATE",
                "social",
                "auth-service",
                userId.toString(),
                "session",
                "127.0.0.1",
                null,
                new UserInfoFromAuthUpdatedEvent.Details(
                        "pwd",
                        "code",
                        "self",
                        "http://localhost",
                        "Updated",
                        "User",
                        email,
                        username
                )
        );
    }

    private UserDeletedFromAuthEvent userDeletedFromAuthEvent(UUID userId) {
        return new UserDeletedFromAuthEvent(
                1_717_171_919_000L,
                "DELETE",
                "social",
                "auth-service",
                userId.toString(),
                "session",
                "127.0.0.1",
                null,
                new UserDeletedFromAuthEvent.Details(
                        "pwd",
                        "code",
                        "self",
                        "http://localhost",
                        "Deleted",
                        "User",
                        "deleted@example.com",
                        "deleted-user"
                )
        );
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

    private UUID deterministicUuid(String namespace, String... components) {
        var seed = namespace + "|" + String.join("|", components);
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
    }
}
