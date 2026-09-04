package com.app.postcommandservice.collab.infrastructure;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
import com.app.postcommandservice.post.infrastructure.entity.BlockReadModelEntity;
import com.app.postcommandservice.post.infrastructure.entity.BlockReadModelId;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class CollabControllerFlowTest {

    private static final UUID CREATOR_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

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
                .to(new org.springframework.amqp.core.TopicExchange(
                        rabbitMQProperties.getExchange().getPost().getEvents()))
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
        assertThat(postJpaRepository.findAll().getFirst().getPostType().name())
                .isEqualTo("COLAB");
        assertThat(collabJpaRepository.findAll().getFirst().getCollabStatus().name())
                .isEqualTo("OPEN");
        assertThat(collabMemberJpaRepository.findAll().getFirst().getRole().name())
                .isEqualTo("ADMIN");
        assertThat(collabMemberJpaRepository.findAll().getFirst().getCollabMemberStatus().name())
                .isEqualTo("ACCEPTED");

        Message message = receiveMessage(queueName);
        assertThat(message).isNotNull();

        var eventPayload = objectMapper.readValue(
                message.getBody(),
                new TypeReference<Map<String, Object>>() { }
        );

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

        var collabBody = objectMapper.readValue(
                collabResponse,
                new TypeReference<Map<String, Object>>() { }
        );

        var basicBody = objectMapper.readValue(
                basicPostResponse,
                new TypeReference<Map<String, Object>>() { }
        );

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

        var firstBody = objectMapper.readValue(
                firstResponse,
                new TypeReference<Map<String, Object>>() { }
        );

        var secondBody = objectMapper.readValue(
                secondResponse,
                new TypeReference<Map<String, Object>>() { }
        );

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
    void shouldAcceptPendingJoinRequestAndPublishEvent() throws Exception {
        var collabId = UUID.randomUUID();
        var targetUserId = UUID.randomUUID();
        seedCollab(collabId, CREATOR_ID);
        seedCollabMember(collabId, CREATOR_ID, CollabMemberStatus.ACCEPTED, CollabMemberRole.ADMIN);
        seedCollabMember(collabId, targetUserId, CollabMemberStatus.PENDING, CollabMemberRole.MEMBER);

        String queueName = "test.collab.request.accepted." + UUID.randomUUID();
        RabbitAdmin rabbitAdmin = new RabbitAdmin(connectionFactory);
        Queue queue = new Queue(queueName, false, true, true);
        rabbitAdmin.declareQueue(queue);
        rabbitAdmin.declareBinding(BindingBuilder.bind(queue)
                .to(new org.springframework.amqp.core.TopicExchange(rabbitMQProperties.getExchange().getPost().getEvents()))
                .with(rabbitMQProperties.getRk().getPost().getCollab().getRequest().getAccepted()));

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

        rabbitAdmin.deleteQueue(queueName);
    }

    @Test
    void shouldReturnForbiddenWhenActioningUserIsNotAcceptedAdmin() throws Exception {
        var collabId = UUID.randomUUID();
        var targetUserId = UUID.randomUUID();
        seedCollab(collabId, CREATOR_ID);
        seedCollabMember(collabId, CREATOR_ID, CollabMemberStatus.ACCEPTED, CollabMemberRole.MEMBER);
        seedCollabMember(collabId, targetUserId, CollabMemberStatus.PENDING, CollabMemberRole.MEMBER);

        mockMvc.perform(put("/api/collabs/{collabId}/requests/{userId}/accept", collabId, targetUserId)
                        .with(jwtFor(CREATOR_ID)))
                .andExpect(status().isForbidden());

        var unchangedMember = collabMemberJpaRepository.findById(new CollabMemberId(collabId, targetUserId)).orElseThrow();
        assertThat(unchangedMember.getCollabMemberStatus()).isEqualTo(CollabMemberStatus.PENDING);
        assertThat(outboxEventRepository.count()).isZero();
    }

    @Test
    void shouldReturnBadRequestWhenTargetMemberIsNotPending() throws Exception {
        var collabId = UUID.randomUUID();
        var targetUserId = UUID.randomUUID();
        seedCollab(collabId, CREATOR_ID);
        seedCollabMember(collabId, CREATOR_ID, CollabMemberStatus.ACCEPTED, CollabMemberRole.ADMIN);
        seedCollabMember(collabId, targetUserId, CollabMemberStatus.ACCEPTED, CollabMemberRole.MEMBER);

        mockMvc.perform(put("/api/collabs/{collabId}/requests/{userId}/accept", collabId, targetUserId)
                        .with(jwtFor(CREATOR_ID)))
                .andExpect(status().isBadRequest());

        assertThat(outboxEventRepository.count()).isZero();
    }

    @Test
    void shouldReturnNotFoundWhenTargetMemberDoesNotExist() throws Exception {
        var collabId = UUID.randomUUID();
        var targetUserId = UUID.randomUUID();
        seedCollab(collabId, CREATOR_ID);
        seedCollabMember(collabId, CREATOR_ID, CollabMemberStatus.ACCEPTED, CollabMemberRole.ADMIN);

        mockMvc.perform(put("/api/collabs/{collabId}/requests/{userId}/accept", collabId, targetUserId)
                        .with(jwtFor(CREATOR_ID)))
                .andExpect(status().isNotFound());

        assertThat(outboxEventRepository.count()).isZero();
    }

    @Test
    void shouldEmitOnlyOneAcceptedEventWhenTwoAdminsAcceptConcurrently() throws Exception {
        var collabId = UUID.randomUUID();
        var firstAdminId = CREATOR_ID;
        var secondAdminId = UUID.randomUUID();
        var targetUserId = UUID.randomUUID();
        seedCollab(collabId, firstAdminId);
        seedCollabMember(collabId, firstAdminId, CollabMemberStatus.ACCEPTED, CollabMemberRole.ADMIN);
        seedCollabMember(collabId, secondAdminId, CollabMemberStatus.ACCEPTED, CollabMemberRole.ADMIN);
        seedCollabMember(collabId, targetUserId, CollabMemberStatus.PENDING, CollabMemberRole.MEMBER);

        String queueName = "test.collab.request.accepted.concurrent." + UUID.randomUUID();
        RabbitAdmin rabbitAdmin = new RabbitAdmin(connectionFactory);
        Queue queue = new Queue(queueName, false, true, true);
        rabbitAdmin.declareQueue(queue);
        rabbitAdmin.declareBinding(BindingBuilder.bind(queue)
                .to(new org.springframework.amqp.core.TopicExchange(rabbitMQProperties.getExchange().getPost().getEvents()))
                .with(rabbitMQProperties.getRk().getPost().getCollab().getRequest().getAccepted()));

        var readyLatch = new CountDownLatch(2);
        var startLatch = new CountDownLatch(1);

        try (ExecutorService executorService = Executors.newFixedThreadPool(2)) {
            List<Future<Integer>> futures = new ArrayList<>();
            futures.add(executorService.submit(concurrentAcceptJoinRequest(collabId, targetUserId, firstAdminId, readyLatch,
                    startLatch)));
            futures.add(executorService.submit(concurrentAcceptJoinRequest(collabId, targetUserId, secondAdminId, readyLatch,
                    startLatch)));

            assertThat(readyLatch.await(5, TimeUnit.SECONDS)).isTrue();
            startLatch.countDown();

            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get());
            }

            assertThat(statuses).containsExactlyInAnyOrder(200, 400);
        }

        var acceptedMember = collabMemberJpaRepository.findById(new CollabMemberId(collabId, targetUserId)).orElseThrow();
        assertThat(acceptedMember.getCollabMemberStatus()).isEqualTo(CollabMemberStatus.ACCEPTED);
        assertThat(outboxEventRepository.count()).isEqualTo(1);

        List<Message> messages = receiveMessages(queueName, 2, 5000L);
        assertThat(messages).hasSize(1);
        var eventPayload = objectMapper.readValue(messages.getFirst().getBody(), new TypeReference<Map<String, Object>>() { });
        assertThat(eventPayload.get("collabId")).isEqualTo(collabId.toString());
        assertThat(eventPayload.get("userId")).isEqualTo(targetUserId.toString());
        assertThat(Set.of(firstAdminId.toString(), secondAdminId.toString())).contains(String.valueOf(eventPayload.get("acceptedBy")));
        rabbitAdmin.deleteQueue(queueName);
    }

    @Test
    void shouldCancelPendingJoinRequestAndPublishEvent() throws Exception {
        var collabId = UUID.randomUUID();
        var requesterId = UUID.randomUUID();
        seedCollab(collabId, CREATOR_ID);
        seedCollabMember(collabId, CREATOR_ID, CollabMemberStatus.ACCEPTED, CollabMemberRole.ADMIN);
        seedCollabMember(collabId, requesterId, CollabMemberStatus.PENDING, CollabMemberRole.MEMBER);

        String queueName = declareEventQueue(
                "test.collab.request.deleted.",
                rabbitMQProperties.getRk().getPost().getCollab().getRequest().getDeleted()
        );

        mockMvc.perform(delete("/api/collabs/{collabId}/requests", collabId)
                        .with(jwtFor(requesterId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.collabId").value(collabId.toString()))
                .andExpect(jsonPath("$.userId").value(requesterId.toString()))
                .andExpect(jsonPath("$.collabMemberStatus").value("DELETED"))
                .andExpect(jsonPath("$.role").value("MEMBER"));

        var updatedMember = collabMemberJpaRepository.findById(new CollabMemberId(collabId, requesterId)).orElseThrow();
        assertThat(updatedMember.getCollabMemberStatus()).isEqualTo(CollabMemberStatus.DELETED);
        assertThat(collabMemberJpaRepository.count()).isEqualTo(2);
        assertThat(outboxEventRepository.count()).isEqualTo(1);

        Message message = receiveMessage(queueName);
        assertThat(message).isNotNull();
        var eventPayload = objectMapper.readValue(message.getBody(), new TypeReference<Map<String, Object>>() { });
        assertThat(eventPayload.get("collabId")).isEqualTo(collabId.toString());
        assertThat(eventPayload.get("userId")).isEqualTo(requesterId.toString());
        assertThat(eventPayload.get("deletedBy")).isEqualTo(requesterId.toString());
        assertThat(eventPayload.get("collabMemberStatus")).isEqualTo("DELETED");

        deleteQueue(queueName);
    }

    @Test
    void shouldDeclinePendingJoinRequestAndPublishEvent() throws Exception {
        var collabId = UUID.randomUUID();
        var targetUserId = UUID.randomUUID();
        seedCollab(collabId, CREATOR_ID);
        seedCollabMember(collabId, CREATOR_ID, CollabMemberStatus.ACCEPTED, CollabMemberRole.ADMIN);
        seedCollabMember(collabId, targetUserId, CollabMemberStatus.PENDING, CollabMemberRole.MEMBER);

        String queueName = "test.collab.request.declined." + UUID.randomUUID();
        RabbitAdmin rabbitAdmin = new RabbitAdmin(connectionFactory);
        Queue queue = new Queue(queueName, false, true, true);
        rabbitAdmin.declareQueue(queue);
        rabbitAdmin.declareBinding(BindingBuilder.bind(queue)
                .to(new org.springframework.amqp.core.TopicExchange(rabbitMQProperties.getExchange().getPost().getEvents()))
                .with(rabbitMQProperties.getRk().getPost().getCollab().getRequest().getDeclined()));

        mockMvc.perform(put("/api/collabs/{collabId}/requests/{userId}/decline", collabId, targetUserId)
                        .with(jwtFor(CREATOR_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.collabId").value(collabId.toString()))
                .andExpect(jsonPath("$.userId").value(targetUserId.toString()))
                .andExpect(jsonPath("$.collabMemberStatus").value("REJECTED"))
                .andExpect(jsonPath("$.role").value("MEMBER"));

        var updatedMember = collabMemberJpaRepository.findById(new CollabMemberId(collabId, targetUserId)).orElseThrow();
        assertThat(updatedMember.getCollabMemberStatus()).isEqualTo(CollabMemberStatus.REJECTED);
        assertThat(outboxEventRepository.count()).isEqualTo(1);

        Message message = receiveMessage(queueName);
        assertThat(message).isNotNull();
        var eventPayload = objectMapper.readValue(message.getBody(), new TypeReference<Map<String, Object>>() { });
        assertThat(eventPayload.get("collabId")).isEqualTo(collabId.toString());
        assertThat(eventPayload.get("userId")).isEqualTo(targetUserId.toString());
        assertThat(eventPayload.get("declinedBy")).isEqualTo(CREATOR_ID.toString());
        assertThat(eventPayload.get("collabMemberStatus")).isEqualTo("REJECTED");

        rabbitAdmin.deleteQueue(queueName);
    }

    @Test
    void shouldReturnForbiddenWhenDecliningUserMembershipIsMissing() throws Exception {
        var collabId = UUID.randomUUID();
        var actioningUserId = UUID.randomUUID();
        var targetUserId = UUID.randomUUID();
        seedCollab(collabId, CREATOR_ID);
        seedCollabMember(collabId, targetUserId, CollabMemberStatus.PENDING, CollabMemberRole.MEMBER);

        mockMvc.perform(put("/api/collabs/{collabId}/requests/{userId}/decline", collabId, targetUserId)
                        .with(jwtFor(actioningUserId)))
                .andExpect(status().isForbidden());

        var unchangedMember = collabMemberJpaRepository.findById(new CollabMemberId(collabId, targetUserId)).orElseThrow();
        assertThat(unchangedMember.getCollabMemberStatus()).isEqualTo(CollabMemberStatus.PENDING);
        assertThat(outboxEventRepository.count()).isZero();
    }

    @Test
    void shouldReturnForbiddenWhenDecliningUserIsNotAcceptedAdmin() throws Exception {
        var collabId = UUID.randomUUID();
        var targetUserId = UUID.randomUUID();
        seedCollab(collabId, CREATOR_ID);
        seedCollabMember(collabId, CREATOR_ID, CollabMemberStatus.ACCEPTED, CollabMemberRole.MEMBER);
        seedCollabMember(collabId, targetUserId, CollabMemberStatus.PENDING, CollabMemberRole.MEMBER);

        mockMvc.perform(put("/api/collabs/{collabId}/requests/{userId}/decline", collabId, targetUserId)
                        .with(jwtFor(CREATOR_ID)))
                .andExpect(status().isForbidden());

        var unchangedMember = collabMemberJpaRepository.findById(new CollabMemberId(collabId, targetUserId)).orElseThrow();
        assertThat(unchangedMember.getCollabMemberStatus()).isEqualTo(CollabMemberStatus.PENDING);
        assertThat(outboxEventRepository.count()).isZero();
    }

    @ParameterizedTest
    @EnumSource(value = CollabMemberStatus.class, names = {"PENDING", "REJECTED", "LEFT", "BANNED"})
    void shouldReturnForbiddenWhenDecliningUserIsNotAccepted(CollabMemberStatus actioningStatus) throws Exception {
        var collabId = UUID.randomUUID();
        var targetUserId = UUID.randomUUID();
        seedCollab(collabId, CREATOR_ID);
        seedCollabMember(collabId, CREATOR_ID, actioningStatus, CollabMemberRole.ADMIN);
        seedCollabMember(collabId, targetUserId, CollabMemberStatus.PENDING, CollabMemberRole.MEMBER);

        mockMvc.perform(put("/api/collabs/{collabId}/requests/{userId}/decline", collabId, targetUserId)
                        .with(jwtFor(CREATOR_ID)))
                .andExpect(status().isForbidden());

        var unchangedMember = collabMemberJpaRepository.findById(new CollabMemberId(collabId, targetUserId)).orElseThrow();
        assertThat(unchangedMember.getCollabMemberStatus()).isEqualTo(CollabMemberStatus.PENDING);
        assertThat(outboxEventRepository.count()).isZero();
    }

    @Test
    void shouldReturnBadRequestWhenDeclineTargetMemberIsNotPending() throws Exception {
        var collabId = UUID.randomUUID();
        var targetUserId = UUID.randomUUID();
        seedCollab(collabId, CREATOR_ID);
        seedCollabMember(collabId, CREATOR_ID, CollabMemberStatus.ACCEPTED, CollabMemberRole.ADMIN);
        seedCollabMember(collabId, targetUserId, CollabMemberStatus.ACCEPTED, CollabMemberRole.MEMBER);

        mockMvc.perform(put("/api/collabs/{collabId}/requests/{userId}/decline", collabId, targetUserId)
                        .with(jwtFor(CREATOR_ID)))
                .andExpect(status().isBadRequest());

        var unchangedMember = collabMemberJpaRepository.findById(new CollabMemberId(collabId, targetUserId)).orElseThrow();
        assertThat(unchangedMember.getCollabMemberStatus()).isEqualTo(CollabMemberStatus.ACCEPTED);
        assertThat(outboxEventRepository.count()).isZero();
    }

    @Test
    void shouldReturnNotFoundWhenDeclineTargetMembershipDoesNotExist() throws Exception {
        var collabId = UUID.randomUUID();
        var targetUserId = UUID.randomUUID();
        seedCollab(collabId, CREATOR_ID);
        seedCollabMember(collabId, CREATOR_ID, CollabMemberStatus.ACCEPTED, CollabMemberRole.ADMIN);

        mockMvc.perform(put("/api/collabs/{collabId}/requests/{userId}/decline", collabId, targetUserId)
                        .with(jwtFor(CREATOR_ID)))
                .andExpect(status().isNotFound());

        assertThat(collabMemberJpaRepository.count()).isEqualTo(1);
        assertThat(outboxEventRepository.count()).isZero();
    }

    @Test
    void shouldRequestToJoinCollabPersistPendingMemberAndPublishEvent() throws Exception {
        var collabId = seedOpenCollab(CREATOR_ID);
        seedAcceptedMember(collabId, CREATOR_ID, true);
        seedAcceptedMember(collabId, UUID.randomUUID(), false);

        String queueName = "test.collab.request.created." + UUID.randomUUID();
        RabbitAdmin rabbitAdmin = new RabbitAdmin(connectionFactory);
        Queue queue = new Queue(queueName, false, true, true);

        rabbitAdmin.declareQueue(queue);
        rabbitAdmin.declareBinding(BindingBuilder.bind(queue)
                .to(new org.springframework.amqp.core.TopicExchange(
                        rabbitMQProperties.getExchange().getPost().getEvents()))
                .with(rabbitMQProperties.getRk().getPost().getCollab().getRequest().getCreated()));

        var requesterId = UUID.randomUUID();

        mockMvc.perform(post("/api/collabs/{collabId}/requests", collabId)
                        .with(jwtFor(requesterId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.collabId").value(collabId.toString()))
                .andExpect(jsonPath("$.userId").value(requesterId.toString()))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.role").value("MEMBER"))
                .andExpect(jsonPath("$.createdAt").exists());

        var members = collabMemberJpaRepository.findAll();

        assertThat(members).hasSize(3);
        assertThat(members.stream()
                .filter(member -> member.getId().getUserId().equals(requesterId))
                .findFirst()
                .orElseThrow()
                .getCollabMemberStatus()
                .name())
                .isEqualTo("PENDING");

        assertThat(outboxEventRepository.count()).isEqualTo(1);

        Message message = receiveMessage(queueName);
        assertThat(message).isNotNull();

        var eventPayload = objectMapper.readValue(
                message.getBody(),
                new TypeReference<Map<String, Object>>() { }
        );

        assertThat(eventPayload.get("collabId")).isEqualTo(collabId.toString());
        assertThat(eventPayload.get("userId")).isEqualTo(requesterId.toString());
        assertThat(eventPayload.get("status")).isEqualTo("PENDING");
        assertThat(eventPayload.get("role")).isEqualTo("MEMBER");

        rabbitAdmin.deleteQueue(queueName);
    }

    @Test
    void shouldCloseOpenCollabAndPublishClosedEvent() throws Exception {
        String queueName = "test.collab.closed." + UUID.randomUUID();
        RabbitAdmin rabbitAdmin = new RabbitAdmin(connectionFactory);
        Queue queue = new Queue(queueName, false, true, true);

        rabbitAdmin.declareQueue(queue);
        rabbitAdmin.declareBinding(BindingBuilder.bind(queue)
                .to(new org.springframework.amqp.core.TopicExchange(
                        rabbitMQProperties.getExchange().getPost().getEvents()))
                .with(rabbitMQProperties.getRk().getPost().getCollab().getClosed()));

        var collabId = seedCollab(ColabStatus.OPEN);
        seedMember(
                collabId,
                CREATOR_ID,
                CollabMemberRole.ADMIN,
                CollabMemberStatus.ACCEPTED
        );

        mockMvc.perform(put("/api/collabs/{collabId}/close", collabId)
                        .with(jwtFor(CREATOR_ID)))
                .andExpect(status().isNoContent());

        var savedCollab = collabJpaRepository.findById(collabId).orElseThrow();

        assertThat(savedCollab.getCollabStatus()).isEqualTo(ColabStatus.CLOSED);
        assertThat(outboxEventRepository.count()).isEqualTo(1);

        Message message = receiveMessage(queueName);
        assertThat(message).isNotNull();

        var eventPayload = objectMapper.readValue(
                message.getBody(),
                new TypeReference<Map<String, Object>>() { }
        );

        assertThat(eventPayload.get("collabId")).isEqualTo(collabId.toString());
        assertThat(eventPayload.get("closedBy")).isEqualTo(CREATOR_ID.toString());
        assertThat(eventPayload.get("collabStatus")).isEqualTo("CLOSED");

        rabbitAdmin.deleteQueue(queueName);
    }

    @Test
    void shouldReturnExistingMemberIdempotentlyWhenRequestAlreadyExists() throws Exception {
        var collabId = seedOpenCollab(CREATOR_ID);
        var requesterId = UUID.randomUUID();

        seedAcceptedMember(collabId, CREATOR_ID, true);

        var createdAt = Instant.now().minusSeconds(60);

        collabMemberJpaRepository.save(new CollabMemberEntity(
                new CollabMemberId(collabId, requesterId),
                CollabMemberStatus.PENDING,
                CollabMemberRole.MEMBER,
                createdAt
        ));

        var firstResponse = mockMvc.perform(post("/api/collabs/{collabId}/requests", collabId)
                        .with(jwtFor(requesterId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        var secondResponse = mockMvc.perform(post("/api/collabs/{collabId}/requests", collabId)
                        .with(jwtFor(requesterId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        var firstBody = objectMapper.readValue(
                firstResponse,
                new TypeReference<Map<String, Object>>() { }
        );

        var secondBody = objectMapper.readValue(
                secondResponse,
                new TypeReference<Map<String, Object>>() { }
        );

        assertThat(secondBody).isEqualTo(firstBody);
        assertThat(collabMemberJpaRepository.count()).isEqualTo(2);
        assertThat(outboxEventRepository.count()).isZero();
    }

    @Test
    void shouldReturnAcceptedWithoutSideEffectsWhenCollabIsAlreadyClosed() throws Exception {
        var collabId = seedCollab(ColabStatus.CLOSED);

        seedMember(
                collabId,
                CREATOR_ID,
                CollabMemberRole.ADMIN,
                CollabMemberStatus.ACCEPTED
        );

        mockMvc.perform(patch("/api/collabs/{collabId}/close", collabId)
                        .with(jwtFor(CREATOR_ID)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$").doesNotExist());

        var savedCollab = collabJpaRepository.findById(collabId).orElseThrow();

        assertThat(savedCollab.getCollabStatus()).isEqualTo(ColabStatus.CLOSED);
        assertThat(outboxEventRepository.count()).isZero();
    }

    @Test
    void shouldReturnBadRequestWhenBlockedRelationshipExistsWithAnyMember() throws Exception {
        var collabId = seedOpenCollab(CREATOR_ID);
        var requesterId = UUID.randomUUID();
        var blockedMemberId = UUID.randomUUID();

        seedAcceptedMember(collabId, CREATOR_ID, true);
        seedAcceptedMember(collabId, blockedMemberId, false);

        blockReadModelJpaRepository.save(new BlockReadModelEntity(
                new BlockReadModelId(blockedMemberId, requesterId),
                Instant.now()
        ));

        mockMvc.perform(post("/api/collabs/{collabId}/requests", collabId)
                        .with(jwtFor(requesterId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BLOCKED"));

        assertThat(collabMemberJpaRepository.count()).isEqualTo(2);
        assertThat(outboxEventRepository.count()).isZero();
    }

    @Test
    void shouldReturnForbiddenWhenRequesterIsNotAdminMember() throws Exception {
        var collabId = seedCollab(ColabStatus.OPEN);

        seedMember(
                collabId,
                CREATOR_ID,
                CollabMemberRole.MEMBER,
                CollabMemberStatus.ACCEPTED
        );

        mockMvc.perform(put("/api/collabs/{collabId}/close", collabId)
                        .with(jwtFor(CREATOR_ID)))
                .andExpect(status().isForbidden());

        var savedCollab = collabJpaRepository.findById(collabId).orElseThrow();

        assertThat(savedCollab.getCollabStatus()).isEqualTo(ColabStatus.OPEN);
        assertThat(outboxEventRepository.count()).isZero();
    }

    @Test
    void shouldReturnBadRequestWhenCollabCreatorRequestsToJoinOwnCollab() throws Exception {
        var collabId = seedOpenCollab(CREATOR_ID);

        seedAcceptedMember(collabId, CREATOR_ID, true);

        mockMvc.perform(post("/api/collabs/{collabId}/requests", collabId)
                        .with(jwtFor(CREATOR_ID)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));

        assertThat(collabMemberJpaRepository.count()).isEqualTo(1);
        assertThat(outboxEventRepository.count()).isZero();
    }

    @Test
    void shouldReturnForbiddenWhenRequesterHasNoMembershipRow() throws Exception {
        var collabId = seedCollab(ColabStatus.OPEN);

        mockMvc.perform(put("/api/collabs/{collabId}/close", collabId)
                        .with(jwtFor(CREATOR_ID)))
                .andExpect(status().isForbidden());

        var savedCollab = collabJpaRepository.findById(collabId).orElseThrow();

        assertThat(savedCollab.getCollabStatus()).isEqualTo(ColabStatus.OPEN);
        assertThat(collabMemberJpaRepository.count()).isZero();
        assertThat(outboxEventRepository.count()).isZero();
    }

    @Test
    void shouldReturnBadRequestWhenCollabIsClosed() throws Exception {
        var collabId = UUID.randomUUID();

        collabJpaRepository.save(new CollabEntity(
                collabId,
                "Closed collab",
                CREATOR_ID,
                ColabStatus.CLOSED,
                Instant.now()
        ));

        seedAcceptedMember(collabId, CREATOR_ID, true);

        mockMvc.perform(post("/api/collabs/{collabId}/requests", collabId)
                        .with(jwtFor(UUID.randomUUID())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));

        assertThat(collabMemberJpaRepository.count()).isEqualTo(1);
    }

    @Test
    void shouldReturnForbiddenWhenRequesterMembershipIsLeft() throws Exception {
        var collabId = seedCollab(ColabStatus.OPEN);

        seedMember(
                collabId,
                CREATOR_ID,
                CollabMemberRole.ADMIN,
                CollabMemberStatus.LEFT
        );

        mockMvc.perform(put("/api/collabs/{collabId}/close", collabId)
                        .with(jwtFor(CREATOR_ID)))
                .andExpect(status().isForbidden());

        var savedCollab = collabJpaRepository.findById(collabId).orElseThrow();

        assertThat(savedCollab.getCollabStatus()).isEqualTo(ColabStatus.OPEN);
        assertThat(outboxEventRepository.count()).isZero();
    }

    @Test
    void shouldReturnForbiddenWhenRequesterMembershipIsBanned() throws Exception {
        var collabId = seedCollab(ColabStatus.OPEN);

        seedMember(
                collabId,
                CREATOR_ID,
                CollabMemberRole.ADMIN,
                CollabMemberStatus.BANNED
        );

        mockMvc.perform(put("/api/collabs/{collabId}/close", collabId)
                        .with(jwtFor(CREATOR_ID)))
                .andExpect(status().isForbidden());

        var savedCollab = collabJpaRepository.findById(collabId).orElseThrow();

        assertThat(savedCollab.getCollabStatus()).isEqualTo(ColabStatus.OPEN);
        assertThat(outboxEventRepository.count()).isZero();
    }

    @ParameterizedTest
    @EnumSource(value = CollabMemberStatus.class, names = {"ACCEPTED", "REJECTED", "DELETED", "LEFT", "BANNED"})
    void shouldReturnBadRequestWhenCancelTargetMemberIsNotPending(CollabMemberStatus targetStatus) throws Exception {
        var collabId = UUID.randomUUID();
        var requesterId = UUID.randomUUID();
        seedCollab(collabId, CREATOR_ID);
        seedCollabMember(collabId, CREATOR_ID, CollabMemberStatus.ACCEPTED, CollabMemberRole.ADMIN);
        seedCollabMember(collabId, requesterId, targetStatus, CollabMemberRole.MEMBER);

        String queueName = declareEventQueue(
                "test.collab.request.deleted.not-pending.",
                rabbitMQProperties.getRk().getPost().getCollab().getRequest().getDeleted()
        );

        mockMvc.perform(delete("/api/collabs/{collabId}/requests", collabId)
                        .with(jwtFor(requesterId)))
                .andExpect(status().isBadRequest());

        var unchangedMember = collabMemberJpaRepository.findById(new CollabMemberId(collabId, requesterId)).orElseThrow();
        assertThat(unchangedMember.getCollabMemberStatus()).isEqualTo(targetStatus);
        assertThat(outboxEventRepository.count()).isZero();
        assertThat(receiveMessage(queueName)).isNull();

        deleteQueue(queueName);
    }

    @Test
    void shouldReturnNotFoundWhenCancelTargetMembershipDoesNotExist() throws Exception {
        var collabId = UUID.randomUUID();
        var requesterId = UUID.randomUUID();
        seedCollab(collabId, CREATOR_ID);
        seedCollabMember(collabId, CREATOR_ID, CollabMemberStatus.ACCEPTED, CollabMemberRole.ADMIN);

        String queueName = declareEventQueue(
                "test.collab.request.deleted.missing-target.",
                rabbitMQProperties.getRk().getPost().getCollab().getRequest().getDeleted()
        );

        mockMvc.perform(delete("/api/collabs/{collabId}/requests", collabId)
                        .with(jwtFor(requesterId)))
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
  
    @Test
    void shouldReturnNotFoundWhenRequestingToJoinNonExistingCollab() throws Exception {
        mockMvc.perform(post("/api/collabs/{collabId}/requests", UUID.randomUUID())
                        .with(jwtFor(UUID.randomUUID())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
    }

    @Test
    void shouldReturnNotFoundWhenClosingNonExistingCollab() throws Exception {
        var collabId = UUID.randomUUID();

        mockMvc.perform(put("/api/collabs/{collabId}/close", collabId)
                        .with(jwtFor(CREATOR_ID)))
                .andExpect(status().isNotFound());

        assertThat(outboxEventRepository.count()).isZero();
    }

    private void seedUser(UUID userId, String username) {
        var now = Instant.now();

        userReadModelJpaRepository.save(
                new UserReadModelEntity(userId, username, now, now)
        );
    }

    private UUID seedOpenCollab(UUID creatorId) {
        var collabId = UUID.randomUUID();

        collabJpaRepository.save(new CollabEntity(
                collabId,
                "Open collab",
                creatorId,
                ColabStatus.OPEN,
                Instant.now()
        ));

        return collabId;
    }

    private UUID seedCollab(ColabStatus status) {
        var collabId = UUID.randomUUID();

        collabJpaRepository.save(new CollabEntity(
                collabId,
                "Close collab",
                CREATOR_ID,
                status,
                Instant.now()
        ));

        return collabId;
    }

    private void seedAcceptedMember(
            UUID collabId,
            UUID userId,
            boolean admin
    ) {
        collabMemberJpaRepository.save(new CollabMemberEntity(
                new CollabMemberId(collabId, userId),
                CollabMemberStatus.ACCEPTED,
                admin
                        ? CollabMemberRole.ADMIN
                        : CollabMemberRole.MEMBER,
                Instant.now()
        ));
    }

    private void seedMember(
            UUID collabId,
            UUID userId,
            CollabMemberRole role,
            CollabMemberStatus status
    ) {
        collabMemberJpaRepository.save(new CollabMemberEntity(
                new CollabMemberId(collabId, userId),
                status,
                role,
                Instant.now()
        ));
    }

    private void seedCollab(UUID collabId, UUID createdBy) {
        collabJpaRepository.save(new CollabEntity(collabId, "Open collab", createdBy, ColabStatus.OPEN, Instant.now()));
    }

    private void seedCollabMember(
            UUID collabId,
            UUID userId,
            CollabMemberStatus status,
            CollabMemberRole role) {
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

    private Callable<Integer> concurrentAcceptJoinRequest(
            UUID collabId,
            UUID targetUserId,
            UUID adminUserId,
            CountDownLatch readyLatch,
            CountDownLatch startLatch) {
        return () -> {
            readyLatch.countDown();
            startLatch.await(5, TimeUnit.SECONDS);
            return mockMvc.perform(put("/api/collabs/{collabId}/requests/{userId}/accept", collabId, targetUserId)
                            .with(jwtFor(adminUserId)))
                    .andReturn()
                    .getResponse()
                    .getStatus();
        };
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

    private List<Message> receiveMessages(String queueName, int maxMessages, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        List<Message> messages = new ArrayList<>();
        while (System.currentTimeMillis() < deadline && messages.size() < maxMessages) {
            Message message = rabbitTemplate.receive(queueName);
            if (message != null) {
                messages.add(message);
                continue;
            }
            Thread.sleep(100L);
        }
        return messages;
    }

    private void assertTimestampEquivalent(Object firstValue, Object secondValue) {
        assertThat(
                Instant.parse(String.valueOf(firstValue))
                        .truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
        ).isEqualTo(
                Instant.parse(String.valueOf(secondValue))
                        .truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
        );
    }
}
