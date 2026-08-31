package com.app.postcommandservice.collab.infrastructure;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.app.postcommandservice.TestcontainersConfiguration;
import com.app.postcommandservice.collab.domain.model.valueobj.ColabStatus;
import com.app.postcommandservice.collab.domain.model.valueobj.CollabMemberRole;
import com.app.postcommandservice.collab.domain.model.valueobj.CollabMemberStatus;
import com.app.postcommandservice.collab.infrastructure.entity.CollabEntity;
import com.app.postcommandservice.collab.infrastructure.entity.CollabMemberEntity;
import com.app.postcommandservice.collab.infrastructure.entity.CollabMemberId;
import com.app.postcommandservice.collab.infrastructure.repository.CollabJpaRepository;
import com.app.postcommandservice.collab.infrastructure.repository.CollabMemberJpaRepository;
import com.app.postcommandservice.collab.infrastructure.repository.CollabRequestIdempotencyJpaRepository;
import com.app.postcommandservice.post.infrastructure.entity.UserReadModelEntity;
import com.app.postcommandservice.post.infrastructure.repository.BlockReadModelJpaRepository;
import com.app.postcommandservice.post.infrastructure.repository.PostJpaRepository;
import com.app.postcommandservice.post.infrastructure.repository.PostRequestIdempotencyJpaRepository;
import com.app.postcommandservice.post.infrastructure.repository.UserReadModelJpaRepository;
import com.app.postcommandservice.shared.infrastructure.rabbitmq.config.RabbitMQProperties;
import com.app.postcommandservice.shared.infrastructure.repository.OutboxEventRepository;
import com.app.postcommandservice.shared.infrastructure.repository.ProcessedEventsRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class CollabControllerFlowTest {

    private static final UUID CREATOR_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private CollabJpaRepository collabJpaRepository;

    @Autowired
    private CollabMemberJpaRepository collabMemberJpaRepository;

    @Autowired
    private CollabRequestIdempotencyJpaRepository collabRequestIdempotencyJpaRepository;

    @Autowired
    private PostJpaRepository postJpaRepository;

    @Autowired
    private PostRequestIdempotencyJpaRepository postRequestIdempotencyJpaRepository;

    @Autowired
    private UserReadModelJpaRepository userReadModelJpaRepository;

    @Autowired
    private BlockReadModelJpaRepository blockReadModelJpaRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private ProcessedEventsRepository processedEventsRepository;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private ConnectionFactory connectionFactory;

    @Autowired
    private RabbitMQProperties rabbitMQProperties;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUpMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    @AfterEach
    void tearDown() {
        blockReadModelJpaRepository.deleteAll();
        userReadModelJpaRepository.deleteAll();
        postRequestIdempotencyJpaRepository.deleteAll();
        postJpaRepository.deleteAll();
        collabMemberJpaRepository.deleteAll();
        collabRequestIdempotencyJpaRepository.deleteAll();
        collabJpaRepository.deleteAll();
        outboxEventRepository.deleteAll();
        processedEventsRepository.deleteAll();
    }

    @Test
    void shouldOpenCollabPersistMemberPostAndPublishEvent() throws Exception {
        seedUser(UUID.randomUUID(), "alice");
        String queueName = "test.collab.opened." + UUID.randomUUID();
        RabbitAdmin rabbitAdmin = new RabbitAdmin(connectionFactory);
        Queue queue = new Queue(queueName, false, true, true);
        rabbitAdmin.declareQueue(queue);
        rabbitAdmin.declareBinding(BindingBuilder.bind(queue)
                .to(new org.springframework.amqp.core.TopicExchange(rabbitMQProperties.getExchange().getPost().getEvents()))
                .with(rabbitMQProperties.getRk().getPost().getCollab().getOpened()));

        var correlationId = UUID.randomUUID();
        var payload = objectMapper.writeValueAsString(Map.of(
                "correlationId", correlationId,
                "title", "Open collab",
                "description", "hello",
                "taggedUsers", Set.of("alice"),
                "postTags", Set.of("spring")
        ));

        mockMvc.perform(post("/api/collabs")
                        .with(jwtFor(CREATOR_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.collabId").exists())
                .andExpect(jsonPath("$.title").value("Open collab"))
                .andExpect(jsonPath("$.createdBy").value(CREATOR_ID.toString()))
                .andExpect(jsonPath("$.collabStatus").value("OPEN"))
                .andExpect(jsonPath("$.post.postType").value("COLAB"))
                .andExpect(jsonPath("$.post.collabId").exists());

        assertThat(collabJpaRepository.count()).isEqualTo(1);
        assertThat(collabMemberJpaRepository.count()).isEqualTo(1);
        assertThat(collabRequestIdempotencyJpaRepository.count()).isEqualTo(1);
        assertThat(postJpaRepository.count()).isEqualTo(1);
        assertThat(postRequestIdempotencyJpaRepository.count()).isZero();
        assertThat(outboxEventRepository.count()).isEqualTo(1);
        assertThat(postJpaRepository.findAll().getFirst().getPostType().name()).isEqualTo("COLAB");
        assertThat(collabJpaRepository.findAll().getFirst().getCollabStatus().name()).isEqualTo("OPEN");
        assertThat(collabMemberJpaRepository.findAll().getFirst().getRole().name()).isEqualTo("ADMIN");
        assertThat(collabMemberJpaRepository.findAll().getFirst().getCollabMemberStatus().name()).isEqualTo("ACCEPTED");

        Message message = receiveMessage(queueName);
        assertThat(message).isNotNull();
        var eventPayload = objectMapper.readValue(message.getBody(), new TypeReference<Map<String, Object>>() { });
        assertThat(eventPayload.get("title")).isEqualTo("Open collab");
        assertThat(eventPayload.get("postType")).isEqualTo("COLAB");

        rabbitAdmin.deleteQueue(queueName);
    }

    @Test
    void shouldAllowStandardPostCreationWithSameCorrelationIdAfterOpeningCollab() throws Exception {
        var correlationId = UUID.randomUUID();
        var collabPayload = objectMapper.writeValueAsString(Map.of(
                "correlationId", correlationId,
                "title", "Open collab",
                "description", "collab post",
                "taggedUsers", Set.of(),
                "postTags", Set.of("collab")
        ));

        var collabResponse = mockMvc.perform(post("/api/collabs")
                        .with(jwtFor(CREATOR_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(collabPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.post.postType").value("COLAB"))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        var basicPostPayload = objectMapper.writeValueAsString(Map.of(
                "correlationId", correlationId,
                "description", "basic post",
                "taggedUsers", Set.of(),
                "postTags", Set.of("basic")
        ));

        var basicPostResponse = mockMvc.perform(post("/api/posts")
                        .with(jwtFor(CREATOR_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(basicPostPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.postType").value("BASIC"))
                .andExpect(jsonPath("$.collabId").doesNotExist())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        var collabBody = objectMapper.readValue(collabResponse, new TypeReference<Map<String, Object>>() { });
        var basicBody = objectMapper.readValue(basicPostResponse, new TypeReference<Map<String, Object>>() { });
        @SuppressWarnings("unchecked")
        var collabPost = (Map<String, Object>) collabBody.get("post");

        assertThat(basicBody.get("postId")).isNotEqualTo(collabPost.get("postId"));
        assertThat(basicBody.get("postType")).isEqualTo("BASIC");
        assertThat(basicBody.get("collabId")).isNull();
        assertThat(postJpaRepository.count()).isEqualTo(2);
        assertThat(postRequestIdempotencyJpaRepository.count()).isEqualTo(1);
    }

    @Test
    void shouldReturnSameResponseWithoutDuplicateInsertsWhenCorrelationIdIsReused() throws Exception {
        var correlationId = UUID.randomUUID();
        var payload = objectMapper.writeValueAsString(Map.of(
                "correlationId", correlationId,
                "title", "Replay collab",
                "description", "hello",
                "taggedUsers", Set.of(),
                "postTags", Set.of("spring")
        ));

        var firstResponse = mockMvc.perform(post("/api/collabs")
                        .with(jwtFor(CREATOR_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        var secondResponse = mockMvc.perform(post("/api/collabs")
                        .with(jwtFor(CREATOR_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        var firstBody = objectMapper.readValue(firstResponse, new TypeReference<Map<String, Object>>() { });
        var secondBody = objectMapper.readValue(secondResponse, new TypeReference<Map<String, Object>>() { });

        assertThat(secondBody.get("collabId")).isEqualTo(firstBody.get("collabId"));
        assertThat(secondBody.get("title")).isEqualTo(firstBody.get("title"));
        assertThat(secondBody.get("createdBy")).isEqualTo(firstBody.get("createdBy"));
        assertThat(secondBody.get("collabStatus")).isEqualTo(firstBody.get("collabStatus"));
        assertTimestampEquivalent(secondBody.get("createdAt"), firstBody.get("createdAt"));

        @SuppressWarnings("unchecked")
        var firstPost = (Map<String, Object>) firstBody.get("post");
        @SuppressWarnings("unchecked")
        var secondPost = (Map<String, Object>) secondBody.get("post");

        assertThat(secondPost.get("postId")).isEqualTo(firstPost.get("postId"));
        assertThat(secondPost.get("userId")).isEqualTo(firstPost.get("userId"));
        assertThat(secondPost.get("collabId")).isEqualTo(firstPost.get("collabId"));
        assertThat(secondPost.get("postType")).isEqualTo(firstPost.get("postType"));
        assertThat(secondPost.get("description")).isEqualTo(firstPost.get("description"));
        assertThat(secondPost.get("taggedUsers")).isEqualTo(firstPost.get("taggedUsers"));
        assertThat(secondPost.get("postTags")).isEqualTo(firstPost.get("postTags"));
        assertTimestampEquivalent(secondPost.get("createdAt"), firstPost.get("createdAt"));
        assertTimestampEquivalent(secondPost.get("updatedAt"), firstPost.get("updatedAt"));
        assertThat(collabJpaRepository.count()).isEqualTo(1);
        assertThat(collabMemberJpaRepository.count()).isEqualTo(1);
        assertThat(collabRequestIdempotencyJpaRepository.count()).isEqualTo(1);
        assertThat(postJpaRepository.count()).isEqualTo(1);
        assertThat(postRequestIdempotencyJpaRepository.count()).isZero();
        assertThat(outboxEventRepository.count()).isEqualTo(1);
    }

    @Test
    void shouldCreatePendingJoinRequestAndPublishEvent() throws Exception {
        var collabId = UUID.randomUUID();
        var requesterId = UUID.randomUUID();
        seedCollab(collabId, CREATOR_ID);
        seedCollabMember(collabId, CREATOR_ID, CollabMemberStatus.ACCEPTED, CollabMemberRole.ADMIN);
        seedCollabMember(collabId, UUID.randomUUID(), CollabMemberStatus.ACCEPTED, CollabMemberRole.MEMBER);

        String queueName = declareEventQueue(
                "test.collab.request.created.",
                rabbitMQProperties.getRk().getPost().getCollab().getRequest().getCreated()
        );

        mockMvc.perform(post("/api/collabs/{collabId}/requests", collabId)
                        .with(jwtFor(requesterId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.collabId").value(collabId.toString()))
                .andExpect(jsonPath("$.userId").value(requesterId.toString()))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.role").value("MEMBER"));

        var createdMember = collabMemberJpaRepository.findById(new CollabMemberId(collabId, requesterId)).orElseThrow();
        assertThat(createdMember.getCollabMemberStatus()).isEqualTo(CollabMemberStatus.PENDING);
        assertThat(outboxEventRepository.count()).isEqualTo(1);

        Message message = receiveMessage(queueName);
        assertThat(message).isNotNull();
        var eventPayload = objectMapper.readValue(message.getBody(), new TypeReference<Map<String, Object>>() { });
        assertThat(eventPayload.get("collabId")).isEqualTo(collabId.toString());
        assertThat(eventPayload.get("userId")).isEqualTo(requesterId.toString());
        assertThat(eventPayload.get("status")).isEqualTo("PENDING");

        deleteQueue(queueName);
    }

    @Test
    void shouldAcceptPendingJoinRequestAndPublishEvent() throws Exception {
        var collabId = UUID.randomUUID();
        var targetUserId = UUID.randomUUID();
        seedCollab(collabId, CREATOR_ID);
        seedCollabMember(collabId, CREATOR_ID, CollabMemberStatus.ACCEPTED, CollabMemberRole.ADMIN);
        seedCollabMember(collabId, targetUserId, CollabMemberStatus.PENDING, CollabMemberRole.MEMBER);

        String queueName = declareEventQueue(
                "test.collab.request.accepted.",
                rabbitMQProperties.getRk().getPost().getCollab().getRequest().getAccepted()
        );

        mockMvc.perform(put("/api/collabs/{collabId}/requests/{userId}/accept", collabId, targetUserId)
                        .with(jwtFor(CREATOR_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.collabId").value(collabId.toString()))
                .andExpect(jsonPath("$.userId").value(targetUserId.toString()))
                .andExpect(jsonPath("$.collabMemberStatus").value("ACCEPTED"))
                .andExpect(jsonPath("$.role").value("MEMBER"));

        var updatedMember = collabMemberJpaRepository.findById(new CollabMemberId(collabId, targetUserId)).orElseThrow();
        assertThat(updatedMember.getCollabMemberStatus()).isEqualTo(CollabMemberStatus.ACCEPTED);
        assertThat(outboxEventRepository.count()).isEqualTo(1);

        Message message = receiveMessage(queueName);
        assertThat(message).isNotNull();
        var eventPayload = objectMapper.readValue(message.getBody(), new TypeReference<Map<String, Object>>() { });
        assertThat(eventPayload.get("collabId")).isEqualTo(collabId.toString());
        assertThat(eventPayload.get("userId")).isEqualTo(targetUserId.toString());
        assertThat(eventPayload.get("acceptedBy")).isEqualTo(CREATOR_ID.toString());
        assertThat(eventPayload.get("collabMemberStatus")).isEqualTo("ACCEPTED");

        deleteQueue(queueName);
    }

    @Test
    void shouldDeclinePendingJoinRequestAndPublishEvent() throws Exception {
        var collabId = UUID.randomUUID();
        var targetUserId = UUID.randomUUID();
        seedCollab(collabId, CREATOR_ID);
        seedCollabMember(collabId, CREATOR_ID, CollabMemberStatus.ACCEPTED, CollabMemberRole.ADMIN);
        seedCollabMember(collabId, targetUserId, CollabMemberStatus.PENDING, CollabMemberRole.MEMBER);

        String queueName = declareEventQueue(
                "test.collab.request.declined.",
                rabbitMQProperties.getRk().getPost().getCollab().getRequest().getDeclined()
        );

        mockMvc.perform(put("/api/collabs/{collabId}/requests/{userId}/decline", collabId, targetUserId)
                        .with(jwtFor(CREATOR_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.collabId").value(collabId.toString()))
                .andExpect(jsonPath("$.userId").value(targetUserId.toString()))
                .andExpect(jsonPath("$.collabMemberStatus").value("REJECTED"))
                .andExpect(jsonPath("$.role").value("MEMBER"));

        var updatedMember = collabMemberJpaRepository.findById(new CollabMemberId(collabId, targetUserId)).orElseThrow();
        assertThat(updatedMember.getCollabMemberStatus()).isEqualTo(CollabMemberStatus.REJECTED);
        assertThat(collabMemberJpaRepository.count()).isEqualTo(2);
        assertThat(outboxEventRepository.count()).isEqualTo(1);

        Message message = receiveMessage(queueName);
        assertThat(message).isNotNull();
        var eventPayload = objectMapper.readValue(message.getBody(), new TypeReference<Map<String, Object>>() { });
        assertThat(eventPayload.get("collabId")).isEqualTo(collabId.toString());
        assertThat(eventPayload.get("userId")).isEqualTo(targetUserId.toString());
        assertThat(eventPayload.get("declinedBy")).isEqualTo(CREATOR_ID.toString());
        assertThat(eventPayload.get("collabMemberStatus")).isEqualTo("REJECTED");

        deleteQueue(queueName);
    }

    @Test
    void shouldReturnForbiddenWhenDecliningUserMembershipIsMissing() throws Exception {
        var collabId = UUID.randomUUID();
        var actioningUserId = UUID.randomUUID();
        var targetUserId = UUID.randomUUID();
        seedCollab(collabId, CREATOR_ID);
        seedCollabMember(collabId, targetUserId, CollabMemberStatus.PENDING, CollabMemberRole.MEMBER);

        String queueName = declareEventQueue(
                "test.collab.request.declined.missing-actor.",
                rabbitMQProperties.getRk().getPost().getCollab().getRequest().getDeclined()
        );

        mockMvc.perform(put("/api/collabs/{collabId}/requests/{userId}/decline", collabId, targetUserId)
                        .with(jwtFor(actioningUserId)))
                .andExpect(status().isForbidden());

        var unchangedMember = collabMemberJpaRepository.findById(new CollabMemberId(collabId, targetUserId)).orElseThrow();
        assertThat(unchangedMember.getCollabMemberStatus()).isEqualTo(CollabMemberStatus.PENDING);
        assertThat(outboxEventRepository.count()).isZero();
        assertThat(receiveMessage(queueName)).isNull();

        deleteQueue(queueName);
    }

    @Test
    void shouldReturnForbiddenWhenDecliningUserIsNotAcceptedAdmin() throws Exception {
        var collabId = UUID.randomUUID();
        var targetUserId = UUID.randomUUID();
        seedCollab(collabId, CREATOR_ID);
        seedCollabMember(collabId, CREATOR_ID, CollabMemberStatus.ACCEPTED, CollabMemberRole.MEMBER);
        seedCollabMember(collabId, targetUserId, CollabMemberStatus.PENDING, CollabMemberRole.MEMBER);

        String queueName = declareEventQueue(
                "test.collab.request.declined.not-admin.",
                rabbitMQProperties.getRk().getPost().getCollab().getRequest().getDeclined()
        );

        mockMvc.perform(put("/api/collabs/{collabId}/requests/{userId}/decline", collabId, targetUserId)
                        .with(jwtFor(CREATOR_ID)))
                .andExpect(status().isForbidden());

        var unchangedMember = collabMemberJpaRepository.findById(new CollabMemberId(collabId, targetUserId)).orElseThrow();
        assertThat(unchangedMember.getCollabMemberStatus()).isEqualTo(CollabMemberStatus.PENDING);
        assertThat(outboxEventRepository.count()).isZero();
        assertThat(receiveMessage(queueName)).isNull();

        deleteQueue(queueName);
    }

    @ParameterizedTest
    @EnumSource(value = CollabMemberStatus.class, names = {"PENDING", "LEFT", "BANNED"})
    void shouldReturnForbiddenWhenDecliningUserIsNotAccepted(CollabMemberStatus actioningStatus) throws Exception {
        var collabId = UUID.randomUUID();
        var targetUserId = UUID.randomUUID();
        seedCollab(collabId, CREATOR_ID);
        seedCollabMember(collabId, CREATOR_ID, actioningStatus, CollabMemberRole.ADMIN);
        seedCollabMember(collabId, targetUserId, CollabMemberStatus.PENDING, CollabMemberRole.MEMBER);

        String queueName = declareEventQueue(
                "test.collab.request.declined.not-accepted.",
                rabbitMQProperties.getRk().getPost().getCollab().getRequest().getDeclined()
        );

        mockMvc.perform(put("/api/collabs/{collabId}/requests/{userId}/decline", collabId, targetUserId)
                        .with(jwtFor(CREATOR_ID)))
                .andExpect(status().isForbidden());

        var unchangedMember = collabMemberJpaRepository.findById(new CollabMemberId(collabId, targetUserId)).orElseThrow();
        assertThat(unchangedMember.getCollabMemberStatus()).isEqualTo(CollabMemberStatus.PENDING);
        assertThat(outboxEventRepository.count()).isZero();
        assertThat(receiveMessage(queueName)).isNull();

        deleteQueue(queueName);
    }

    @Test
    void shouldReturnBadRequestWhenDeclineTargetMemberIsNotPending() throws Exception {
        var collabId = UUID.randomUUID();
        var targetUserId = UUID.randomUUID();
        seedCollab(collabId, CREATOR_ID);
        seedCollabMember(collabId, CREATOR_ID, CollabMemberStatus.ACCEPTED, CollabMemberRole.ADMIN);
        seedCollabMember(collabId, targetUserId, CollabMemberStatus.ACCEPTED, CollabMemberRole.MEMBER);

        String queueName = declareEventQueue(
                "test.collab.request.declined.not-pending.",
                rabbitMQProperties.getRk().getPost().getCollab().getRequest().getDeclined()
        );

        mockMvc.perform(put("/api/collabs/{collabId}/requests/{userId}/decline", collabId, targetUserId)
                        .with(jwtFor(CREATOR_ID)))
                .andExpect(status().isBadRequest());

        var unchangedMember = collabMemberJpaRepository.findById(new CollabMemberId(collabId, targetUserId)).orElseThrow();
        assertThat(unchangedMember.getCollabMemberStatus()).isEqualTo(CollabMemberStatus.ACCEPTED);
        assertThat(outboxEventRepository.count()).isZero();
        assertThat(receiveMessage(queueName)).isNull();

        deleteQueue(queueName);
    }

    @Test
    void shouldReturnNotFoundWhenDeclineTargetMembershipDoesNotExist() throws Exception {
        var collabId = UUID.randomUUID();
        var targetUserId = UUID.randomUUID();
        seedCollab(collabId, CREATOR_ID);
        seedCollabMember(collabId, CREATOR_ID, CollabMemberStatus.ACCEPTED, CollabMemberRole.ADMIN);

        String queueName = declareEventQueue(
                "test.collab.request.declined.missing-target.",
                rabbitMQProperties.getRk().getPost().getCollab().getRequest().getDeclined()
        );

        mockMvc.perform(put("/api/collabs/{collabId}/requests/{userId}/decline", collabId, targetUserId)
                        .with(jwtFor(CREATOR_ID)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));

        assertThat(collabMemberJpaRepository.count()).isEqualTo(1);
        assertThat(outboxEventRepository.count()).isZero();
        assertThat(receiveMessage(queueName)).isNull();

        deleteQueue(queueName);
    }

    private void seedCollab(UUID collabId, UUID createdBy) {
        collabJpaRepository.save(new CollabEntity(collabId, "Collab", createdBy, ColabStatus.OPEN, Instant.now()));
    }

    private void seedCollabMember(UUID collabId, UUID userId, CollabMemberStatus status, CollabMemberRole role) {
        collabMemberJpaRepository.save(new CollabMemberEntity(new CollabMemberId(collabId, userId), status, role, Instant.now()));
    }

    private void seedUser(UUID userId, String username) {
        var now = Instant.now();
        userReadModelJpaRepository.save(new UserReadModelEntity(userId, username, now, now));
    }

    private SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor jwtFor(UUID userId) {
        return jwt().jwt(jwt -> jwt.subject(userId.toString()));
    }

    private String declareEventQueue(String prefix, String routingKey) {
        String queueName = prefix + UUID.randomUUID();
        RabbitAdmin rabbitAdmin = new RabbitAdmin(connectionFactory);
        Queue queue = new Queue(queueName, false, true, true);
        rabbitAdmin.declareQueue(queue);
        rabbitAdmin.declareBinding(BindingBuilder.bind(queue)
                .to(new org.springframework.amqp.core.TopicExchange(rabbitMQProperties.getExchange().getPost().getEvents()))
                .with(routingKey));
        return queueName;
    }

    private void deleteQueue(String queueName) {
        new RabbitAdmin(connectionFactory).deleteQueue(queueName);
    }

    private Message receiveMessage(String queueName) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        Message message;
        do {
            message = rabbitTemplate.receive(queueName);
            if (message != null) {
                return message;
            }
            Thread.sleep(200L);
        } while (System.currentTimeMillis() < deadline);

        return null;
    }

    private void assertTimestampEquivalent(Object firstValue, Object secondValue) {
        assertThat(Instant.parse(String.valueOf(firstValue)).truncatedTo(java.time.temporal.ChronoUnit.MILLIS))
                .isEqualTo(Instant.parse(String.valueOf(secondValue)).truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
    }
}
