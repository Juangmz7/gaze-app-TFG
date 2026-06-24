package com.app.socialservice.block.infrastructure.controller;

import com.app.socialservice.TestcontainersConfiguration;
import com.app.socialservice.block.infrastructure.entity.BlockEntityId;
import com.app.socialservice.block.infrastructure.repository.JpaBlockRepository;
import com.app.socialservice.follow.infrastructure.entity.FollowEntity;
import com.app.socialservice.follow.infrastructure.entity.FollowEntityId;
import com.app.socialservice.follow.infrastructure.enums.FollowStatus;
import com.app.socialservice.follow.infrastructure.repository.JpaFollowRepository;
import com.app.socialservice.shared.infrastructure.enums.EventStatus;
import com.app.socialservice.shared.infrastructure.rabbitmq.config.RabbitMQProperties;
import com.app.socialservice.shared.infrastructure.repository.OutboxEventRepository;
import com.app.socialservice.user.domain.enums.UserAccountStatus;
import com.app.socialservice.user.infrastructure.entity.UserEntity;
import com.app.socialservice.user.infrastructure.entity.UserNode;
import com.app.socialservice.user.infrastructure.repository.JpaUserRepository;
import com.app.socialservice.user.infrastructure.repository.UserNodeRepository;
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
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class BlockControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JpaUserRepository jpaUserRepository;

    @Autowired
    private UserNodeRepository userNodeRepository;

    @Autowired
    private JpaBlockRepository jpaBlockRepository;

    @Autowired
    private JpaFollowRepository jpaFollowRepository;

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

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void setUp() {
        outboxEventRepository.deleteAll();
        jpaBlockRepository.deleteAll();
        jpaFollowRepository.deleteAll();
        jpaUserRepository.deleteAll();

        neo4jClient.query("MATCH ()-[r]->() DELETE r").run();
        neo4jClient.query("MATCH (n) DELETE n").run();
    }

    @Test
    void postApiSocialBlockReturns200WithBlockBodyWhenRelationshipIsCreated() throws Exception {
        var blockerId = UUID.randomUUID();
        var blockedId = UUID.randomUUID();

        seedUser(blockerId, "blocker");
        seedUser(blockedId, "blocked");
        seedNode(blockerId);
        seedNode(blockedId);
        seedFollow(blockerId, blockedId);
        seedFollow(blockedId, blockerId);
        seedGraphFollow(blockerId, blockedId);

        mockMvc.perform(post("/api/social/block")
                        .with(jwt().jwt(jwt -> jwt.claim("userId", blockerId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(blockRequest(blockedId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blockerId").value(blockerId.toString()))
                .andExpect(jsonPath("$.blockedId").value(blockedId.toString()))
                .andExpect(jsonPath("$.createdAt").exists());

        assertThat(jpaBlockRepository.findById(new BlockEntityId(blockerId, blockedId))).isPresent();
        assertThat(jpaFollowRepository.findById(new FollowEntityId(blockerId, blockedId))).get()
                .extracting(FollowEntity::getStatus)
                .isEqualTo(FollowStatus.BLOCKED);
        assertThat(jpaFollowRepository.findById(new FollowEntityId(blockedId, blockerId))).get()
                .extracting(FollowEntity::getStatus)
                .isEqualTo(FollowStatus.BLOCKED);
        assertThat(outboxEventRepository.findAll()).singleElement()
                .extracting(event -> event.getEventType())
                .isEqualTo("UserBlockedEvent");
        waitForFollowRelationshipsDeletion(blockerId, blockedId);
        assertThat(countFollowRelationships(blockerId, blockedId)).isZero();
    }

    @Test
    void postApiSocialBlockReturns200WhenCalledTwiceWithSameUsers() throws Exception {
        var blockerId = UUID.randomUUID();
        var blockedId = UUID.randomUUID();

        seedUser(blockerId, "blocker-repeat");
        seedUser(blockedId, "blocked-repeat");
        seedNode(blockerId);
        seedNode(blockedId);

        mockMvc.perform(post("/api/social/block")
                        .with(jwt().jwt(jwt -> jwt.claim("userId", blockerId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(blockRequest(blockedId)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/social/block")
                        .with(jwt().jwt(jwt -> jwt.claim("userId", blockerId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(blockRequest(blockedId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blockerId").value(blockerId.toString()))
                .andExpect(jsonPath("$.blockedId").value(blockedId.toString()));

        assertThat(jpaBlockRepository.count()).isEqualTo(1);
        assertThat(outboxEventRepository.count()).isEqualTo(1);
    }

    @Test
    void postApiSocialBlockReturns400WhenBlockerIdEqualsBlockedId() throws Exception {
        var userId = UUID.randomUUID();

        seedUser(userId, "self-block");
        seedNode(userId);

        mockMvc.perform(post("/api/social/block")
                        .with(jwt().jwt(jwt -> jwt.claim("userId", userId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(blockRequest(userId)))
                .andExpect(status().isBadRequest());

        assertThat(jpaBlockRepository.count()).isZero();
    }

    @Test
    void postApiSocialBlockReturns404WhenTargetUserDoesNotExist() throws Exception {
        var blockerId = UUID.randomUUID();
        var missingUserId = UUID.randomUUID();

        seedUser(blockerId, "missing-target");
        seedNode(blockerId);

        mockMvc.perform(post("/api/social/block")
                        .with(jwt().jwt(jwt -> jwt.claim("userId", blockerId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(blockRequest(missingUserId)))
                .andExpect(status().isNotFound());

        assertThat(jpaBlockRepository.count()).isZero();
    }

    @Test
    void postApiSocialBlockPublishesMessageToRabbitMqVerifiableViaEmbeddedBroker() throws Exception {
        var blockerId = UUID.randomUUID();
        var blockedId = UUID.randomUUID();
        var queueName = "q.social-service.test.block." + UUID.randomUUID();
        var queue = QueueBuilder.nonDurable(queueName).exclusive().autoDelete().build();

        seedUser(blockerId, "publisher-blocker");
        seedUser(blockedId, "publisher-blocked");
        seedNode(blockerId);
        seedNode(blockedId);

        amqpAdmin.declareQueue(queue);
        amqpAdmin.declareBinding(BindingBuilder.bind(queue)
                .to(new TopicExchange(rabbitMQProperties.getExchange().getUser().getEvents()))
                .with(rabbitMQProperties.getRk().getUser().getBlock().getCreated()));

        mockMvc.perform(post("/api/social/block")
                        .with(jwt().jwt(jwt -> jwt.claim("userId", blockerId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(blockRequest(blockedId)))
                .andExpect(status().isOk());

        var message = waitForMessage(queueName);

        assertThat(message).isNotNull();
        assertThat(new String(message.getBody(), StandardCharsets.UTF_8))
                .contains(blockerId.toString(), blockedId.toString());
        assertThat(outboxEventRepository.findAll()).singleElement()
                .extracting(event -> event.getStatus())
                .isEqualTo(EventStatus.PROCESSED);
    }

    @Test
    void rabbitMqTopologyDeclaresBlockQueueAndDlq() {
        var blockQueueName = rabbitMQProperties.getQueue().getUser().getBlock().getCreated();

        assertThat(amqpAdmin.getQueueProperties(blockQueueName)).isNotNull();
        assertThat(amqpAdmin.getQueueProperties(blockQueueName + ".dlq")).isNotNull();
    }

    @Test
    void deleteApiSocialBlockReturns200AndRemovesBlockAndBlockedFollows() throws Exception {
        var unblockerId = UUID.randomUUID();
        var unblockedId = UUID.randomUUID();

        seedUser(unblockerId, "unblocker");
        seedUser(unblockedId, "unblocked");
        seedBlockedFollow(unblockerId, unblockedId);

        mockMvc.perform(delete("/api/social/block")
                        .with(jwt().jwt(jwt -> jwt.claim("userId", unblockerId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(blockRequest(unblockedId)))
                .andExpect(status().isOk());

        assertThat(jpaBlockRepository.findById(new BlockEntityId(unblockerId, unblockedId))).isEmpty();
        assertThat(jpaFollowRepository.findById(new FollowEntityId(unblockerId, unblockedId))).get()
                .extracting(FollowEntity::getStatus)
                .isEqualTo(FollowStatus.REMOVED);
        assertThat(jpaFollowRepository.findById(new FollowEntityId(unblockedId, unblockerId))).get()
                .extracting(FollowEntity::getStatus)
                .isEqualTo(FollowStatus.REMOVED);
        assertThat(outboxEventRepository.findAll()).singleElement()
                .extracting(event -> event.getEventType(), event -> event.getStatus())
                .containsExactly("UserUnblockedEvent", EventStatus.PROCESSED);
    }

    @Test
    void deleteApiSocialBlockReturns200WhenRelationshipDoesNotExist() throws Exception {
        var unblockerId = UUID.randomUUID();
        var unblockedId = UUID.randomUUID();

        seedUser(unblockerId, "unblocker-missing-block");
        seedUser(unblockedId, "unblocked-missing-block");
        seedFollow(unblockerId, unblockedId);

        mockMvc.perform(delete("/api/social/block")
                        .with(jwt().jwt(jwt -> jwt.claim("userId", unblockerId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(blockRequest(unblockedId)))
                .andExpect(status().isOk());

        assertThat(jpaBlockRepository.count()).isZero();
        assertThat(jpaFollowRepository.findById(new FollowEntityId(unblockerId, unblockedId))).get()
                .extracting(FollowEntity::getStatus)
                .isEqualTo(FollowStatus.ACTIVE);
        assertThat(outboxEventRepository.count()).isZero();
    }

    @Test
    void deleteApiSocialBlockReturns400WhenUnblockerIdEqualsUnblockedId() throws Exception {
        var userId = UUID.randomUUID();

        seedUser(userId, "self-unblock");

        mockMvc.perform(delete("/api/social/block")
                        .with(jwt().jwt(jwt -> jwt.claim("userId", userId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(blockRequest(userId)))
                .andExpect(status().isBadRequest());

        assertThat(jpaBlockRepository.count()).isZero();
        assertThat(outboxEventRepository.count()).isZero();
    }

    @Test
    void deleteApiSocialBlockReturns404WhenTargetUserDoesNotExist() throws Exception {
        var unblockerId = UUID.randomUUID();
        var missingUserId = UUID.randomUUID();

        seedUser(unblockerId, "unblocker-missing-target");

        mockMvc.perform(delete("/api/social/block")
                        .with(jwt().jwt(jwt -> jwt.claim("userId", unblockerId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(blockRequest(missingUserId)))
                .andExpect(status().isNotFound());

        assertThat(jpaBlockRepository.count()).isZero();
        assertThat(outboxEventRepository.count()).isZero();
    }

    @Test
    void deleteApiSocialBlockPublishesMessageToRabbitMqVerifiableViaEmbeddedBroker() throws Exception {
        var unblockerId = UUID.randomUUID();
        var unblockedId = UUID.randomUUID();
        var queueName = "q.social-service.test.unblock." + UUID.randomUUID();
        var queue = QueueBuilder.nonDurable(queueName).exclusive().autoDelete().build();

        seedUser(unblockerId, "publisher-unblocker");
        seedUser(unblockedId, "publisher-unblocked");
        seedBlockedFollow(unblockerId, unblockedId);

        amqpAdmin.declareQueue(queue);
        amqpAdmin.declareBinding(BindingBuilder.bind(queue)
                .to(new TopicExchange(rabbitMQProperties.getExchange().getUser().getEvents()))
                .with(rabbitMQProperties.getRk().getUser().getBlock().getDeleted()));

        mockMvc.perform(delete("/api/social/block")
                        .with(jwt().jwt(jwt -> jwt.claim("userId", unblockerId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(blockRequest(unblockedId)))
                .andExpect(status().isOk());

        var message = waitForMessage(queueName);

        assertThat(message).isNotNull();
        assertThat(new String(message.getBody(), StandardCharsets.UTF_8)).contains(
                unblockerId.toString(),
                unblockedId.toString()
        );
        assertThat(outboxEventRepository.findAll()).singleElement()
                .extracting(event -> event.getStatus())
                .isEqualTo(EventStatus.PROCESSED);
    }

    private String blockRequest(UUID blockedId) {
        return """
                {"blockedUserId":"%s"}
                """.formatted(blockedId);
    }

    private void seedUser(UUID userId, String username) {
        jpaUserRepository.save(UserEntity.builder()
                .id(userId)
                .username(username)
                .email(username + "@example.com")
                .accountStatus(UserAccountStatus.ACCEPTED)
                .build());
    }

    private void seedNode(UUID userId) {
        userNodeRepository.save(UserNode.builder().id(userId).build());
    }

    private void seedFollow(UUID followerId, UUID followedId) {
        jpaFollowRepository.save(new FollowEntity(
                new FollowEntityId(followerId, followedId),
                FollowStatus.ACTIVE,
                Instant.now(),
                null
        ));
    }

    private void seedBlockedFollow(UUID firstUserId, UUID secondUserId) {
        jpaBlockRepository.save(new com.app.socialservice.block.infrastructure.entity.BlockEntity(
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

    private void seedGraphFollow(UUID firstUserId, UUID secondUserId) {
        neo4jClient.query("""
                MATCH (first:User {id: $firstUserId}), (second:User {id: $secondUserId})
                MERGE (first)-[:FOLLOWS]->(second)
                MERGE (second)-[:FOLLOWS]->(first)
                """)
                .bind(firstUserId.toString()).to("firstUserId")
                .bind(secondUserId.toString()).to("secondUserId")
                .run();
    }

    private long countFollowRelationships(UUID firstUserId, UUID secondUserId) {
        var result = neo4jClient.query("""
                MATCH (from:User)-[follow:FOLLOWS]->(to:User)
                WHERE (from.id = $firstUserId AND to.id = $secondUserId)
                   OR (from.id = $secondUserId AND to.id = $firstUserId)
                RETURN count(follow) AS relationships
                """)
                .bind(firstUserId.toString()).to("firstUserId")
                .bind(secondUserId.toString()).to("secondUserId")
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

    private void waitForFollowRelationshipsDeletion(UUID firstUserId, UUID secondUserId) throws InterruptedException {
        for (int attempt = 0; attempt < 20; attempt++) {
            if (countFollowRelationships(firstUserId, secondUserId) == 0L) {
                return;
            }
            Thread.sleep(200);
        }
    }
}
