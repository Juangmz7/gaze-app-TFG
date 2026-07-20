package com.app.socialservice.follow.infrastructure.controller;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import com.app.socialservice.block.infrastructure.repository.JpaBlockRepository;
import com.app.socialservice.TestcontainersConfiguration;
import com.app.socialservice.follow.infrastructure.entity.FollowEntityId;
import com.app.socialservice.follow.infrastructure.enums.FollowStatus;
import com.app.socialservice.follow.infrastructure.repository.JpaFollowRepository;
import com.app.socialservice.follow.infrastructure.events.UserFollowedEvent;
import com.app.socialservice.follow.infrastructure.events.UserUnfollowedEvent;
import com.app.socialservice.follow.testutil.FollowMother;
import com.app.socialservice.shared.infrastructure.enums.EventStatus;
import com.app.socialservice.shared.infrastructure.rabbitmq.config.RabbitMQProperties;
import com.app.socialservice.shared.infrastructure.repository.OutboxEventRepository;
import com.app.socialservice.shared.testutil.SocialIntegrationSeeder;
import com.app.socialservice.shared.testutil.UserStatsTestConstants;
import com.app.socialservice.user.infrastructure.repository.JpaUserRepository;
import com.app.socialservice.user.infrastructure.repository.UserNodeRepository;
import com.app.socialservice.user.testutil.UserMother;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class FollowControllerIntegrationTest {

    private static final String BLOCKED_ERROR_CODE = "BLOCKED";
    private static final Instant DEFAULT_CREATED_AT = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant DEFAULT_UPDATED_AT = Instant.parse("2026-01-01T00:04:00Z");
    private static final Instant DEFAULT_BLOCKED_AT = Instant.parse("2026-01-01T00:05:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JpaUserRepository jpaUserRepository;

    @Autowired
    private UserNodeRepository userNodeRepository;

    @Autowired
    private JpaFollowRepository jpaFollowRepository;

    @Autowired
    private JpaBlockRepository jpaBlockRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private Neo4jClient neo4jClient;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @Autowired
    private RabbitMQProperties rabbitMQProperties;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void setUp() {
        outboxEventRepository.deleteAll();
        jpaFollowRepository.deleteAll();
        jpaBlockRepository.deleteAll();
        jpaUserRepository.deleteAll();

        neo4jClient.query("MATCH ()-[r]->() DELETE r").run();
        neo4jClient.query("MATCH (n) DELETE n").run();
        flushRedis();
    }

    @Test
    void postApiSocialFollowReturns200WithFollowBodyWhenRelationshipIsCreated() throws Exception {
        var followerId = UUID.randomUUID();
        var followedId = UUID.randomUUID();

        seedUser(followerId, "follower");
        seedUser(followedId, "followed");
        seedNode(followerId);
        seedNode(followedId);

        mockMvc.perform(post("/api/social/follow")
                        .with(jwt().jwt(jwt -> jwt.subject(followerId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(followRequest(followedId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.followerId").value(followerId.toString()))
                .andExpect(jsonPath("$.followedId").value(followedId.toString()))
                .andExpect(jsonPath("$.createdAt").exists());

        assertThat(jpaFollowRepository.findById(new FollowEntityId(followerId, followedId))).get()
                .extracting(follow -> follow.getStatus())
                .isEqualTo(FollowStatus.ACTIVE);
        assertThat(outboxEventRepository.findAll()).singleElement()
                .extracting(event -> event.getEventType())
                .isEqualTo(UserFollowedEvent.class.getSimpleName());
        waitForFollowRelationshipCreation(followerId, followedId);
        assertThat(countFollowRelationships(followerId, followedId)).isEqualTo(1L);
        waitForCounterValue(followerId, UserStatsTestConstants.FOLLOWING_COUNTER, 1L);
        waitForCounterValue(followedId, UserStatsTestConstants.FOLLOWERS_COUNTER, 1L);
        assertThat(readCounter(followerId, UserStatsTestConstants.FOLLOWERS_COUNTER)).isZero();
        assertThat(readCounter(followedId, UserStatsTestConstants.FOLLOWING_COUNTER)).isZero();
    }

    @Test
    void postApiSocialFollowReturns200WhenCalledTwiceWithSameUsers() throws Exception {
        var followerId = UUID.randomUUID();
        var followedId = UUID.randomUUID();

        seedUser(followerId, "follower-repeat");
        seedUser(followedId, "followed-repeat");
        seedNode(followerId);
        seedNode(followedId);

        mockMvc.perform(post("/api/social/follow")
                        .with(jwt().jwt(jwt -> jwt.subject(followerId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(followRequest(followedId)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/social/follow")
                        .with(jwt().jwt(jwt -> jwt.subject(followerId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(followRequest(followedId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.followerId").value(followerId.toString()))
                .andExpect(jsonPath("$.followedId").value(followedId.toString()));

        assertThat(jpaFollowRepository.count()).isEqualTo(1);
        assertThat(outboxEventRepository.count()).isEqualTo(1);
        waitForFollowRelationshipCreation(followerId, followedId);
        assertThat(countFollowRelationships(followerId, followedId)).isEqualTo(1L);
        waitForCounterValue(followerId, UserStatsTestConstants.FOLLOWING_COUNTER, 1L);
        waitForCounterValue(followedId, UserStatsTestConstants.FOLLOWERS_COUNTER, 1L);
    }

    @Test
    void postApiSocialFollowReactivatesRemovedRelationshipAndPublishesEvent() throws Exception {
        var followerId = UUID.randomUUID();
        var followedId = UUID.randomUUID();

        seedUser(followerId, "follower-removed");
        seedUser(followedId, "followed-removed");
        seedNode(followerId);
        seedNode(followedId);
        seedRemovedFollow(followerId, followedId);

        mockMvc.perform(post("/api/social/follow")
                        .with(jwt().jwt(jwt -> jwt.subject(followerId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(followRequest(followedId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.followerId").value(followerId.toString()))
                .andExpect(jsonPath("$.followedId").value(followedId.toString()));

        assertThat(jpaFollowRepository.findById(new FollowEntityId(followerId, followedId))).get()
                .extracting(follow -> follow.getStatus())
                .isEqualTo(FollowStatus.ACTIVE);
        assertThat(jpaFollowRepository.count()).isEqualTo(1);
        assertThat(outboxEventRepository.count()).isEqualTo(1);
        waitForFollowRelationshipCreation(followerId, followedId);
        assertThat(countFollowRelationships(followerId, followedId)).isEqualTo(1L);
        waitForCounterValue(followerId, UserStatsTestConstants.FOLLOWING_COUNTER, 1L);
        waitForCounterValue(followedId, UserStatsTestConstants.FOLLOWERS_COUNTER, 1L);
    }

    @Test
    void postApiSocialFollowReturns403WhenRelationshipIsBlocked() throws Exception {
        var followerId = UUID.randomUUID();
        var followedId = UUID.randomUUID();

        seedUser(followerId, "follower-blocked");
        seedUser(followedId, "followed-blocked");
        seedNode(followerId);
        seedNode(followedId);
        seedBlockedFollow(followerId, followedId);

        mockMvc.perform(post("/api/social/follow")
                        .with(jwt().jwt(jwt -> jwt.subject(followerId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(followRequest(followedId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value(BLOCKED_ERROR_CODE));

        assertThat(jpaFollowRepository.findById(new FollowEntityId(followerId, followedId))).get()
                .extracting(follow -> follow.getStatus())
                .isEqualTo(FollowStatus.BLOCKED);
        assertThat(outboxEventRepository.count()).isZero();
        assertThat(countFollowRelationships(followerId, followedId)).isZero();
        assertThat(readCounter(followerId, UserStatsTestConstants.FOLLOWING_COUNTER)).isZero();
        assertThat(readCounter(followedId, UserStatsTestConstants.FOLLOWERS_COUNTER)).isZero();
    }

    @Test
    void postApiSocialFollowReturns403WhenUsersAreBlockedBeforeAnyFollowRowExists() throws Exception {
        var followerId = UUID.randomUUID();
        var followedId = UUID.randomUUID();

        seedUser(followerId, "follower-block-first");
        seedUser(followedId, "followed-block-first");
        seedNode(followerId);
        seedNode(followedId);
        seedBlock(followerId, followedId);

        mockMvc.perform(post("/api/social/follow")
                        .with(jwt().jwt(jwt -> jwt.subject(followerId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(followRequest(followedId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value(BLOCKED_ERROR_CODE));

        assertThat(jpaFollowRepository.count()).isZero();
        assertThat(outboxEventRepository.count()).isZero();
        assertThat(countFollowRelationships(followerId, followedId)).isZero();
        assertThat(readCounter(followerId, UserStatsTestConstants.FOLLOWING_COUNTER)).isZero();
        assertThat(readCounter(followedId, UserStatsTestConstants.FOLLOWERS_COUNTER)).isZero();
    }

    @Test
    void postApiSocialFollowReturns400WhenFollowerIdEqualsFollowedId() throws Exception {
        var userId = UUID.randomUUID();

        seedUser(userId, "self-follow");
        seedNode(userId);

        mockMvc.perform(post("/api/social/follow")
                        .with(jwt().jwt(jwt -> jwt.subject(userId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(followRequest(userId)))
                .andExpect(status().isBadRequest());

        assertThat(jpaFollowRepository.count()).isZero();
        assertThat(readCounter(userId, UserStatsTestConstants.FOLLOWING_COUNTER)).isZero();
    }

    @Test
    void postApiSocialFollowReturns404WhenTargetUserDoesNotExist() throws Exception {
        var followerId = UUID.randomUUID();
        var missingUserId = UUID.randomUUID();

        seedUser(followerId, "missing-target-follower");
        seedNode(followerId);

        mockMvc.perform(post("/api/social/follow")
                        .with(jwt().jwt(jwt -> jwt.subject(followerId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(followRequest(missingUserId)))
                .andExpect(status().isNotFound());

        assertThat(jpaFollowRepository.count()).isZero();
        assertThat(readCounter(followerId, UserStatsTestConstants.FOLLOWING_COUNTER)).isZero();
        assertThat(outboxEventRepository.count()).isZero();
    }

    @Test
    void postApiSocialFollowPublishesMessageToRabbitMqVerifiableViaEmbeddedBroker() throws Exception {
        var followerId = UUID.randomUUID();
        var followedId = UUID.randomUUID();
        var queueName = "q.social-service.test.follow." + UUID.randomUUID();
        var queue = QueueBuilder.nonDurable(queueName).exclusive().autoDelete().build();

        seedUser(followerId, "publisher-follower");
        seedUser(followedId, "publisher-followed");
        seedNode(followerId);
        seedNode(followedId);

        amqpAdmin.declareQueue(queue);
        amqpAdmin.declareBinding(BindingBuilder.bind(queue)
                .to(new TopicExchange(rabbitMQProperties.getExchange().getUser().getEvents()))
                .with(rabbitMQProperties.getRk().getUser().getFollow().getCreated()));

        mockMvc.perform(post("/api/social/follow")
                        .with(jwt().jwt(jwt -> jwt.subject(followerId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(followRequest(followedId)))
                .andExpect(status().isOk());

        var message = waitForMessage(queueName);

        assertThat(message).isNotNull();
        assertThat(new String(message.getBody(), StandardCharsets.UTF_8)).contains(
                followerId.toString(),
                followedId.toString()
        );
        assertThat(outboxEventRepository.findAll()).singleElement()
                .extracting(event -> event.getStatus())
                .isEqualTo(EventStatus.PROCESSED);
        waitForFollowRelationshipCreation(followerId, followedId);
        assertThat(countFollowRelationships(followerId, followedId)).isEqualTo(1L);
    }

    @Test
    void deleteApiSocialFollowReturns204WhenRelationshipIsDeleted() throws Exception {
        var followerId = UUID.randomUUID();
        var followedId = UUID.randomUUID();

        seedUser(followerId, "unfollow-follower");
        seedUser(followedId, "unfollow-followed");
        seedNode(followerId);
        seedNode(followedId);
        seedActiveFollow(followerId, followedId);
        seedFollowRelationship(followerId, followedId);
        seedUserStats(followerId, 1L, 0L);
        seedUserStats(followedId, 0L, 1L);

        mockMvc.perform(delete("/api/social/follow")
                        .with(jwt().jwt(jwt -> jwt.subject(followerId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(followRequest(followedId)))
                .andExpect(status().isNoContent());

        assertThat(jpaFollowRepository.findById(new FollowEntityId(followerId, followedId))).get()
                .extracting(follow -> follow.getStatus())
                .isEqualTo(FollowStatus.REMOVED);
        assertThat(outboxEventRepository.findAll()).singleElement()
                .extracting(event -> event.getEventType())
                .isEqualTo(UserUnfollowedEvent.class.getSimpleName());
        waitForFollowRelationshipDeletion(followerId, followedId);
        assertThat(countFollowRelationships(followerId, followedId)).isZero();
        waitForCounterValue(followerId, UserStatsTestConstants.FOLLOWING_COUNTER, 0L);
        waitForCounterValue(followedId, UserStatsTestConstants.FOLLOWERS_COUNTER, 0L);
    }

    @Test
    void deleteApiSocialFollowReturns204WhenCalledOnANonExistingRelationship() throws Exception {
        var followerId = UUID.randomUUID();
        var followedId = UUID.randomUUID();

        seedUser(followerId, "missing-unfollow-follower");
        seedUser(followedId, "missing-unfollow-followed");
        seedNode(followerId);
        seedNode(followedId);

        mockMvc.perform(delete("/api/social/follow")
                        .with(jwt().jwt(jwt -> jwt.subject(followerId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(followRequest(followedId)))
                .andExpect(status().isNoContent());

        assertThat(jpaFollowRepository.count()).isZero();
        assertThat(outboxEventRepository.count()).isZero();
        assertThat(countFollowRelationships(followerId, followedId)).isZero();
    }

    @Test
    void deleteApiSocialFollowReturns400WhenFollowerIdEqualsFollowedId() throws Exception {
        var userId = UUID.randomUUID();

        seedUser(userId, "self-unfollow");
        seedNode(userId);

        mockMvc.perform(delete("/api/social/follow")
                        .with(jwt().jwt(jwt -> jwt.subject(userId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(followRequest(userId)))
                .andExpect(status().isBadRequest());

        assertThat(jpaFollowRepository.count()).isZero();
        assertThat(outboxEventRepository.count()).isZero();
    }

    @Test
    void deleteApiSocialFollowReturns404WhenTargetUserDoesNotExist() throws Exception {
        var followerId = UUID.randomUUID();
        var missingUserId = UUID.randomUUID();

        seedUser(followerId, "missing-unfollow-target-follower");
        seedNode(followerId);

        mockMvc.perform(delete("/api/social/follow")
                        .with(jwt().jwt(jwt -> jwt.subject(followerId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(followRequest(missingUserId)))
                .andExpect(status().isNotFound());

        assertThat(jpaFollowRepository.count()).isZero();
        assertThat(outboxEventRepository.count()).isZero();
    }

    @Test
    void deleteApiSocialFollowPublishesUnfollowMessageToRabbitMqVerifiableViaEmbeddedBroker() throws Exception {
        var followerId = UUID.randomUUID();
        var followedId = UUID.randomUUID();
        var queueName = "q.social-service.test.unfollow." + UUID.randomUUID();
        var queue = QueueBuilder.nonDurable(queueName).exclusive().autoDelete().build();

        seedUser(followerId, "unfollow-publisher-follower");
        seedUser(followedId, "unfollow-publisher-followed");
        seedNode(followerId);
        seedNode(followedId);
        seedActiveFollow(followerId, followedId);
        seedFollowRelationship(followerId, followedId);
        seedUserStats(followerId, 1L, 0L);
        seedUserStats(followedId, 0L, 1L);

        amqpAdmin.declareQueue(queue);
        amqpAdmin.declareBinding(BindingBuilder.bind(queue)
                .to(new TopicExchange(rabbitMQProperties.getExchange().getUser().getEvents()))
                .with(rabbitMQProperties.getRk().getUser().getFollow().getDeleted()));

        mockMvc.perform(delete("/api/social/follow")
                        .with(jwt().jwt(jwt -> jwt.subject(followerId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(followRequest(followedId)))
                .andExpect(status().isNoContent());

        var message = waitForMessage(queueName);

        assertThat(message).isNotNull();
        assertThat(new String(message.getBody(), StandardCharsets.UTF_8)).contains(
                followerId.toString(),
                followedId.toString()
        );
        assertThat(outboxEventRepository.findAll()).singleElement()
                .extracting(event -> event.getStatus())
                .isEqualTo(EventStatus.PROCESSED);
        waitForFollowRelationshipDeletion(followerId, followedId);
        assertThat(countFollowRelationships(followerId, followedId)).isZero();
    }

    private String followRequest(UUID followedId) {
        return """
                {"followedUserId":"%s"}
                """.formatted(followedId);
    }

    private void seedRemovedFollow(UUID followerId, UUID followedId) {
        SocialIntegrationSeeder.seedFollow(
                jpaFollowRepository,
                FollowMother.removedEntity(followerId, followedId, DEFAULT_CREATED_AT, DEFAULT_UPDATED_AT)
        );
    }

    private void seedActiveFollow(UUID followerId, UUID followedId) {
        SocialIntegrationSeeder.seedFollow(
                jpaFollowRepository,
                FollowMother.activeEntity(followerId, followedId, DEFAULT_CREATED_AT, DEFAULT_UPDATED_AT)
        );
    }

    private void seedBlockedFollow(UUID followerId, UUID followedId) {
        seedBlock(followerId, followedId);
        SocialIntegrationSeeder.seedFollow(
                jpaFollowRepository,
                FollowMother.blockedEntity(followerId, followedId, DEFAULT_CREATED_AT, DEFAULT_UPDATED_AT)
        );
    }

    private void seedBlock(UUID blockerId, UUID blockedId) {
        SocialIntegrationSeeder.seedBlock(jpaBlockRepository, blockerId, blockedId, DEFAULT_BLOCKED_AT);
    }

    private void seedFollowRelationship(UUID followerId, UUID followedId) {
        SocialIntegrationSeeder.seedGraphFollow(neo4jClient, followerId, followedId);
    }

    private void seedUserStats(UUID userId, long followingCount, long followerCount) {
        SocialIntegrationSeeder.seedUserStats(stringRedisTemplate, userId, followerCount, followingCount, 0L);
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

    private Message waitForMessage(String queueName) throws InterruptedException {
        rabbitTemplate.setReceiveTimeout(200);

        for (int attempt = 0; attempt < 20; attempt++) {
            var message = rabbitTemplate.receive(queueName);
            if (message != null) {
                return message;
            }
            Thread.sleep(200);
        }

        return null;
    }

    private void waitForFollowRelationshipCreation(UUID followerId, UUID followedId) throws InterruptedException {
        for (int attempt = 0; attempt < 20; attempt++) {
            if (countFollowRelationships(followerId, followedId) == 1L) {
                return;
            }
            Thread.sleep(200);
        }
    }

    private void waitForFollowRelationshipDeletion(UUID followerId, UUID followedId) throws InterruptedException {
        for (int attempt = 0; attempt < 20; attempt++) {
            if (countFollowRelationships(followerId, followedId) == 0L) {
                return;
            }
            Thread.sleep(200);
        }
    }

    private void waitForCounterValue(UUID userId, String counterName, long expectedValue) throws InterruptedException {
        for (int attempt = 0; attempt < 20; attempt++) {
            if (readCounter(userId, counterName) == expectedValue) {
                return;
            }
            Thread.sleep(200);
        }
    }

    private long readCounter(UUID userId, String counterName) {
        return SocialIntegrationSeeder.readCounter(stringRedisTemplate, userId, counterName);
    }

    private void flushRedis() {
        var connection = stringRedisTemplate.getConnectionFactory().getConnection();
        try {
            connection.serverCommands().flushDb();
        } finally {
            connection.close();
        }
    }

    private void seedUser(UUID userId, String username) {
        SocialIntegrationSeeder.seedUser(jpaUserRepository, UserMother.acceptedEntity(userId, username));
    }

    private void seedNode(UUID userId) {
        SocialIntegrationSeeder.seedUserNode(userNodeRepository, userId);
    }
}
