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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
    void shouldLeaveCollabViaPostAndPublishEvent() throws Exception {
        String queueName = "test.collab.member.left." + UUID.randomUUID();
        RabbitAdmin rabbitAdmin = new RabbitAdmin(connectionFactory);
        Queue queue = new Queue(queueName, false, true, true);
        rabbitAdmin.declareQueue(queue);
        rabbitAdmin.declareBinding(BindingBuilder.bind(queue)
                .to(new org.springframework.amqp.core.TopicExchange(rabbitMQProperties.getExchange().getPost().getEvents()))
                .with(rabbitMQProperties.getRk().getPost().getCollab().getMember().getLeft()));

        var collabId = seedCollab(CREATOR_ID);
        seedMember(collabId, UUID.fromString("22222222-2222-2222-2222-222222222222"),
                CollabMemberStatus.ACCEPTED, CollabMemberRole.MEMBER);

        mockMvc.perform(post("/api/collabs/{collabId}/leave", collabId)
                        .with(jwtFor(UUID.fromString("22222222-2222-2222-2222-222222222222"))))
                .andExpect(status().isOk());

        var persistedMember = collabMemberJpaRepository
                .findById(new CollabMemberId(collabId, UUID.fromString("22222222-2222-2222-2222-222222222222")))
                .orElseThrow();
        assertThat(persistedMember.getCollabMemberStatus()).isEqualTo(CollabMemberStatus.LEFT);
        assertThat(outboxEventRepository.count()).isEqualTo(1);

        Message message = receiveMessage(queueName);
        assertThat(message).isNotNull();
        var eventPayload = objectMapper.readValue(message.getBody(), new TypeReference<Map<String, Object>>() { });
        assertThat(eventPayload.get("collabId")).isEqualTo(collabId.toString());
        assertThat(eventPayload.get("userId")).isEqualTo("22222222-2222-2222-2222-222222222222");
        assertThat(eventPayload.get("collabMemberStatus")).isEqualTo("LEFT");

        rabbitAdmin.deleteQueue(queueName);
    }

    @Test
    void shouldLeaveCollabViaDeleteAndReturnNoContent() throws Exception {
        var collabId = seedCollab(CREATOR_ID);
        var memberId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        seedMember(collabId, memberId, CollabMemberStatus.ACCEPTED, CollabMemberRole.MEMBER);

        mockMvc.perform(delete("/api/collabs/{collabId}/leave", collabId)
                        .with(jwtFor(memberId)))
                .andExpect(status().isNoContent());

        var persistedMember = collabMemberJpaRepository.findById(new CollabMemberId(collabId, memberId)).orElseThrow();
        assertThat(persistedMember.getCollabMemberStatus()).isEqualTo(CollabMemberStatus.LEFT);
        assertThat(outboxEventRepository.count()).isEqualTo(1);
    }

    @Test
    void shouldReturnBadRequestWhenAdminAttemptsToLeaveCollab() throws Exception {
        var collabId = seedCollab(CREATOR_ID);
        seedMember(collabId, CREATOR_ID, CollabMemberStatus.ACCEPTED, CollabMemberRole.ADMIN);

        mockMvc.perform(post("/api/collabs/{collabId}/leave", collabId)
                        .with(jwtFor(CREATOR_ID)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Admin user %s cannot leave collab %s"
                        .formatted(CREATOR_ID, collabId)));

        var persistedMember = collabMemberJpaRepository.findById(new CollabMemberId(collabId, CREATOR_ID)).orElseThrow();
        assertThat(persistedMember.getCollabMemberStatus()).isEqualTo(CollabMemberStatus.ACCEPTED);
        assertThat(outboxEventRepository.count()).isZero();
    }

    @Test
    void shouldReturnBadRequestWhenMemberIsNotAccepted() throws Exception {
        var collabId = seedCollab(CREATOR_ID);
        var memberId = UUID.fromString("44444444-4444-4444-4444-444444444444");
        seedMember(collabId, memberId, CollabMemberStatus.BANNED, CollabMemberRole.MEMBER);

        mockMvc.perform(post("/api/collabs/{collabId}/leave", collabId)
                        .with(jwtFor(memberId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Collab member %s for collab %s must be ACCEPTED to leave, but was BANNED"
                        .formatted(memberId, collabId)));

        var persistedMember = collabMemberJpaRepository.findById(new CollabMemberId(collabId, memberId)).orElseThrow();
        assertThat(persistedMember.getCollabMemberStatus()).isEqualTo(CollabMemberStatus.BANNED);
        assertThat(outboxEventRepository.count()).isZero();
    }

    @Test
    void shouldReturnNotFoundWhenCollabDoesNotExist() throws Exception {
        var collabId = UUID.randomUUID();

        mockMvc.perform(post("/api/collabs/{collabId}/leave", collabId)
                        .with(jwtFor(CREATOR_ID)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Collab not found with id: %s".formatted(collabId)));

        assertThat(outboxEventRepository.count()).isZero();
    }

    @Test
    void shouldReturnNotFoundWhenMembershipDoesNotExist() throws Exception {
        var collabId = seedCollab(CREATOR_ID);
        var memberId = UUID.fromString("55555555-5555-5555-5555-555555555555");

        mockMvc.perform(post("/api/collabs/{collabId}/leave", collabId)
                        .with(jwtFor(memberId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Collab member not found for collab %s and user %s"
                        .formatted(collabId, memberId)));

        assertThat(outboxEventRepository.count()).isZero();
    }

    @Test
    void shouldRejectMalformedCollabIdOnPostLeaveWithoutCreatingOutboxOrMessage() throws Exception {
        String queueName = declareMemberLeftQueue();

        mockMvc.perform(post("/api/collabs/{collabId}/leave", "not-a-uuid")
                        .with(jwtFor(CREATOR_ID)))
                .andExpect(status().isBadRequest());

        assertThat(outboxEventRepository.count()).isZero();
        assertThat(receiveMessage(queueName)).isNull();

        new RabbitAdmin(connectionFactory).deleteQueue(queueName);
    }

    @Test
    void shouldRejectMalformedCollabIdOnDeleteLeaveWithoutCreatingOutboxOrMessage() throws Exception {
        String queueName = declareMemberLeftQueue();

        mockMvc.perform(delete("/api/collabs/{collabId}/leave", "not-a-uuid")
                        .with(jwtFor(CREATOR_ID)))
                .andExpect(status().isBadRequest());

        assertThat(outboxEventRepository.count()).isZero();
        assertThat(receiveMessage(queueName)).isNull();

        new RabbitAdmin(connectionFactory).deleteQueue(queueName);
    }

    private void seedUser(UUID userId, String username) {
        var now = Instant.now();
        userReadModelJpaRepository.save(new UserReadModelEntity(userId, username, now, now));
    }

    private UUID seedCollab(UUID createdBy) {
        var collabId = UUID.randomUUID();
        collabJpaRepository.save(new CollabEntity(
                collabId,
                "Seeded collab",
                createdBy,
                ColabStatus.OPEN,
                Instant.now()
        ));
        return collabId;
    }

    private void seedMember(UUID collabId, UUID userId, CollabMemberStatus status, CollabMemberRole role) {
        collabMemberJpaRepository.save(new CollabMemberEntity(
                new CollabMemberId(collabId, userId),
                status,
                role,
                Instant.now()
        ));
    }

    private SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor jwtFor(UUID userId) {
        return jwt().jwt(jwt -> jwt.subject(userId.toString()));
    }

    private String declareMemberLeftQueue() {
        String queueName = "test.collab.member.left." + UUID.randomUUID();
        RabbitAdmin rabbitAdmin = new RabbitAdmin(connectionFactory);
        Queue queue = new Queue(queueName, false, true, true);
        rabbitAdmin.declareQueue(queue);
        rabbitAdmin.declareBinding(BindingBuilder.bind(queue)
                .to(new org.springframework.amqp.core.TopicExchange(rabbitMQProperties.getExchange().getPost().getEvents()))
                .with(rabbitMQProperties.getRk().getPost().getCollab().getMember().getLeft()));
        return queueName;
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
