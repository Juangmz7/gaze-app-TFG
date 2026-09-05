package com.app.postcommandservice.post.infrastructure.controller;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.core.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import com.app.postcommandservice.TestcontainersConfiguration;
import com.app.postcommandservice.collab.domain.model.valueobj.ColabStatus;
import com.app.postcommandservice.collab.infrastructure.entity.CollabEntity;
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
import com.app.postcommandservice.collab.domain.model.valueobj.CollabMemberRole;
import com.app.postcommandservice.collab.domain.model.valueobj.CollabMemberStatus;
import com.app.postcommandservice.collab.infrastructure.entity.CollabMemberEntity;
import com.app.postcommandservice.collab.infrastructure.entity.CollabMemberId;
import com.app.postcommandservice.shared.infrastructure.rabbitmq.config.RabbitMQProperties;
import com.app.postcommandservice.shared.infrastructure.repository.OutboxEventRepository;
import com.app.postcommandservice.shared.infrastructure.repository.ProcessedEventsRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.app.postcommandservice.post.domain.model.valueobj.PostStatus;
import com.app.postcommandservice.post.domain.model.valueobj.PostType;

@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class PostControllerIT {

    private static final UUID CREATOR_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext webApplicationContext;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private PostJpaRepository postJpaRepository;

    @Autowired
    private CollabJpaRepository collabJpaRepository;

    @Autowired
    private CollabMemberJpaRepository collabMemberJpaRepository;

    @Autowired
    private CollabRequestIdempotencyJpaRepository collabRequestIdempotencyJpaRepository;

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

    @Autowired
    private TransactionTemplate transactionTemplate;

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
    void shouldCreatePostAndPublishEventWhenRequestIsValidWithNoTaggedUsers() throws Exception {
        String queueName = "test.post.created." + UUID.randomUUID();
        RabbitAdmin rabbitAdmin = new RabbitAdmin(connectionFactory);
        Queue queue = new Queue(queueName, false, true, true);
        rabbitAdmin.declareQueue(queue);
        rabbitAdmin.declareBinding(BindingBuilder.bind(queue)
                .to(new org.springframework.amqp.core.TopicExchange(rabbitMQProperties.getExchange().getPost().getEvents()))
                .with(rabbitMQProperties.getRk().getPost().getCreated()));

        var correlationId = UUID.randomUUID();
        var payload = objectMapper.writeValueAsString(Map.of(
                "correlationId", correlationId,
                "description", "",
                "taggedUsers", Set.of(),
                "postTags", Set.of("java")
        ));

        var mvcResult = mockMvc.perform(post("/api/posts")
                        .with(jwtFor(CREATOR_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.postId").exists())
                .andExpect(jsonPath("$.userId").value(CREATOR_ID.toString()))
                .andExpect(jsonPath("$.collabId").doesNotExist())
                .andExpect(jsonPath("$.postType").value("BASIC"))
                .andExpect(jsonPath("$.description").value(""))
                .andExpect(jsonPath("$.taggedUsers").isArray())
                .andExpect(jsonPath("$.postTags[0]").value("java"))
                .andReturn();

        assertThat(postJpaRepository.findAll()).hasSize(1);
        assertThat(postJpaRepository.findAll().getFirst().getStatus().name()).isEqualTo("ACTIVE");
        assertThat(postRequestIdempotencyJpaRepository.findById(correlationId)).isPresent();
        assertThat(outboxEventRepository.findAll()).hasSize(1);

        Message message = receiveMessage(queueName);
        assertThat(message).isNotNull();
        var eventPayload = objectMapper.readValue(message.getBody(), new TypeReference<Map<String, Object>>() { });
        assertThat(eventPayload.get("userId")).isEqualTo(CREATOR_ID.toString());
        assertThat(eventPayload.get("collabId")).isNull();
        assertThat(eventPayload.get("postType")).isEqualTo("BASIC");
        assertThat(eventPayload.get("description")).isEqualTo("");

        rabbitAdmin.deleteQueue(queueName);
    }

    @Test
    void shouldCreatePostSuccessfullyWhenTaggedUsersExistAndAreNotBlocked() throws Exception {
        seedUser(UUID.randomUUID(), "alice");
        seedUser(UUID.randomUUID(), "bob");

        var correlationId = UUID.randomUUID();
        var payload = objectMapper.writeValueAsString(Map.of(
                "correlationId", correlationId,
                "description", "hello",
                "taggedUsers", new LinkedHashSet<>(Set.of("alice", "bob")),
                "postTags", Set.of("spring")
        ));

        mockMvc.perform(post("/api/posts")
                        .with(jwtFor(CREATOR_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("hello"))
                .andExpect(jsonPath("$.taggedUsers").isArray());

        assertThat(postJpaRepository.findAll()).hasSize(1);
    }

    @Test
    void shouldReturnLinkedFalseWhenPostHasNoCollabId() throws Exception {
        var existingPost = seedPost(CREATOR_ID, "standalone", Set.of(), Set.of());

        mockMvc.perform(get("/api/posts/{postId}/collab-status", existingPost.getId())
                        .with(jwtFor(CREATOR_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linked").value(false));
    }

    @Test
    void shouldReturnCollabBodyWhenPostIsLinked() throws Exception {
        var collab = seedCollab(CREATOR_ID, "Team up");
        var linkedPost = postJpaRepository.save(com.app.postcommandservice.post.infrastructure.entity.PostEntity.builder()
                .id(UUID.randomUUID())
                .userId(CREATOR_ID)
                .collabId(collab.getId())
                .postType(PostType.COLAB)
                .description("linked")
                .taggedUsers(new ArrayList<>())
                .tags(new ArrayList<>())
                .status(PostStatus.ACTIVE)
                .build());

        mockMvc.perform(get("/api/posts/{postId}/collab-status", linkedPost.getId())
                        .with(jwtFor(CREATOR_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linked").value(true))
                .andExpect(jsonPath("$.collab.collabId").value(collab.getId().toString()))
                .andExpect(jsonPath("$.collab.title").value("Team up"))
                .andExpect(jsonPath("$.collab.createdBy").value(CREATOR_ID.toString()))
                .andExpect(jsonPath("$.collab.collabStatus").value("OPEN"))
                .andExpect(jsonPath("$.collab.createdAt").exists());
    }

    @Test
    void shouldReturnForbiddenWhenCheckingCollabStatusOfAnotherUsersPost() throws Exception {
        var existingPost = seedPost(UUID.randomUUID(), "hidden", Set.of(), Set.of());

        mockMvc.perform(get("/api/posts/{postId}/collab-status", existingPost.getId())
                        .with(jwtFor(CREATOR_ID)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
    }

    @Test
    void shouldReturnNotFoundWhenCheckingCollabStatusOfMissingPost() throws Exception {
        mockMvc.perform(get("/api/posts/{postId}/collab-status", UUID.randomUUID())
                        .with(jwtFor(CREATOR_ID)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
    }

    @Test
    void shouldUpdatePostAndPublishEventWhenRequestIsValid() throws Exception {
        String queueName = "test.post.updated." + UUID.randomUUID();
        RabbitAdmin rabbitAdmin = new RabbitAdmin(connectionFactory);
        Queue queue = new Queue(queueName, false, true, true);
        rabbitAdmin.declareQueue(queue);
        rabbitAdmin.declareBinding(BindingBuilder.bind(queue)
                .to(new org.springframework.amqp.core.TopicExchange(rabbitMQProperties.getExchange().getPost().getEvents()))
                .with(rabbitMQProperties.getRk().getPost().getUpdated()));

        seedUser(UUID.randomUUID(), "bob");
        var existingPost = seedPost(CREATOR_ID, "before", Set.of("alice"), Set.of("java"));

        var payload = objectMapper.writeValueAsString(Map.of(
                "postId", existingPost.getId(),
                "description", "after",
                "taggedUsers", new LinkedHashSet<>(Set.of("alice", "bob")),
                "postTags", Set.of("spring")
        ));

        var mvcResult = mockMvc.perform(put("/api/posts")
                        .with(jwtFor(CREATOR_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.postId").value(existingPost.getId().toString()))
                .andExpect(jsonPath("$.description").value("after"))
                .andExpect(jsonPath("$.taggedUsers").isArray())
                .andExpect(jsonPath("$.postTags[0]").value("spring"))
                .andReturn();

        var persistedUpdatedAt = transactionTemplate.execute(status -> {
            var updatedPost = postJpaRepository.findById(existingPost.getId()).orElseThrow();
            assertThat(updatedPost.getDescription()).isEqualTo("after");
            assertThat(new LinkedHashSet<>(updatedPost.getTaggedUsers())).containsExactlyInAnyOrder("alice", "bob");
            assertThat(updatedPost.getTags()).containsExactly("spring");
            assertThat(updatedPost.getUpdatedAt()).isAfterOrEqualTo(updatedPost.getCreatedAt());
            return updatedPost.getUpdatedAt();
        });
        assertThat(persistedUpdatedAt).isNotNull();
        assertThat(outboxEventRepository.findAll()).hasSize(1);

        Message message = receiveMessage(queueName);
        assertThat(message).isNotNull();
        var eventPayload = objectMapper.readValue(message.getBody(), new TypeReference<Map<String, Object>>() { });
        var outboxPayload = objectMapper.readValue(
                outboxEventRepository.findAll().getFirst().getPayload(),
                new TypeReference<Map<String, Object>>() { }
        );
        var responsePayload = objectMapper.readValue(
                mvcResult.getResponse().getContentAsString(),
                new TypeReference<Map<String, Object>>() { }
        );
        assertThat(eventPayload.get("description")).isEqualTo("after");
        assertThat(eventPayload.get("createdAt")).isEqualTo(responsePayload.get("createdAt"));
        assertThat(eventPayload.get("updatedAt")).isEqualTo(responsePayload.get("updatedAt"));
        assertTimestampsEquivalent(responsePayload.get("updatedAt"), persistedUpdatedAt.toString());
        assertTimestampsEquivalent(eventPayload.get("updatedAt"), persistedUpdatedAt.toString());
        assertTimestampsEquivalent(outboxPayload.get("updatedAt"), persistedUpdatedAt.toString());

        rabbitAdmin.deleteQueue(queueName);
    }

    @Test
    void shouldReturnExistingPostWithoutWritesOrEventsWhenUpdatePayloadMatchesCurrentState() throws Exception {
        var existingPost = seedPost(CREATOR_ID, "same", Set.of("alice"), Set.of("java"));

        var payload = objectMapper.writeValueAsString(Map.of(
                "postId", existingPost.getId(),
                "description", "same",
                "taggedUsers", Set.of("alice"),
                "postTags", Set.of("java")
        ));

        var response = mockMvc.perform(put("/api/posts")
                        .with(jwtFor(CREATOR_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        var responseBody = objectMapper.readValue(response, new TypeReference<Map<String, Object>>() { });
        var persistedPost = postJpaRepository.findById(existingPost.getId()).orElseThrow();

        assertTimestampsEquivalent(responseBody.get("createdAt"), normalizeTimestamp(existingPost.getCreatedAt()).toString());
        assertTimestampsEquivalent(responseBody.get("updatedAt"), normalizeTimestamp(existingPost.getUpdatedAt()).toString());
        assertTimestampsEquivalent(persistedPost.getUpdatedAt().toString(), existingPost.getUpdatedAt().toString());
        assertThat(outboxEventRepository.count()).isEqualTo(0);
    }

    @Test
    void shouldReturnForbiddenWhenUpdatingPostOwnedByAnotherUser() throws Exception {
        var existingPost = seedPost(UUID.randomUUID(), "before", Set.of(), Set.of());

        var payload = objectMapper.writeValueAsString(Map.of(
                "postId", existingPost.getId(),
                "description", "after",
                "taggedUsers", Set.of(),
                "postTags", Set.of()
        ));

        mockMvc.perform(put("/api/posts")
                        .with(jwtFor(CREATOR_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
    }

    @Test
    void shouldReturnNotFoundWhenNewlyTaggedUsernameDoesNotExistDuringUpdate() throws Exception {
        var existingPost = seedPost(CREATOR_ID, "before", Set.of("alice"), Set.of());

        var payload = objectMapper.writeValueAsString(Map.of(
                "postId", existingPost.getId(),
                "description", "after",
                "taggedUsers", new LinkedHashSet<>(Set.of("alice", "missing")),
                "postTags", Set.of("java")
        ));

        mockMvc.perform(put("/api/posts")
                        .with(jwtFor(CREATOR_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
    }

    @Test
    void shouldReturnForbiddenWhenNewlyTaggedUserIsBlockedDuringUpdate() throws Exception {
        UUID bobId = UUID.randomUUID();
        seedUser(bobId, "bob");
        blockReadModelJpaRepository.save(new BlockReadModelEntity(
                new BlockReadModelId(CREATOR_ID, bobId),
                Instant.now()
        ));
        var existingPost = seedPost(CREATOR_ID, "before", Set.of("alice"), Set.of());

        var payload = objectMapper.writeValueAsString(Map.of(
                "postId", existingPost.getId(),
                "description", "after",
                "taggedUsers", new LinkedHashSet<>(Set.of("alice", "bob")),
                "postTags", Set.of("java")
        ));

        mockMvc.perform(put("/api/posts")
                        .with(jwtFor(CREATOR_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("BLOCKED"));
    }

    @Test
    void shouldDeletePostAndPublishMinimalEventWhenOwnerDeletesAnActivePost() throws Exception {
        String queueName = "test.post.deleted." + UUID.randomUUID();
        RabbitAdmin rabbitAdmin = new RabbitAdmin(connectionFactory);
        Queue queue = new Queue(queueName, false, true, true);
        rabbitAdmin.declareQueue(queue);
        rabbitAdmin.declareBinding(BindingBuilder.bind(queue)
                .to(new org.springframework.amqp.core.TopicExchange(rabbitMQProperties.getExchange().getPost().getEvents()))
                .with(rabbitMQProperties.getRk().getPost().getDeleted()));
        var existingPost = seedPost(CREATOR_ID, "before", Set.of("alice"), Set.of("java"));

        mockMvc.perform(delete("/api/posts/{postId}", existingPost.getId())
                        .with(jwtFor(CREATOR_ID)))
                .andExpect(status().isNoContent());

        var deletedPost = postJpaRepository.findById(existingPost.getId()).orElseThrow();
        assertThat(deletedPost.getStatus()).isEqualTo(PostStatus.DELETED);
        assertThat(outboxEventRepository.findAll()).hasSize(1);

        Message message = receiveMessage(queueName);
        assertThat(message).isNotNull();
        var eventPayload = objectMapper.readValue(message.getBody(), new TypeReference<Map<String, Object>>() { });
        assertThat(eventPayload).hasSize(2);
        assertThat(eventPayload).contains(entry("postId", existingPost.getId().toString()));
        assertThat(eventPayload.get("occurredAt")).isNotNull();

        var outboxPayload = objectMapper.readValue(
                outboxEventRepository.findAll().getFirst().getPayload(),
                new TypeReference<Map<String, Object>>() { }
        );
        assertThat(outboxPayload).hasSize(2);
        assertThat(outboxPayload).contains(entry("postId", existingPost.getId().toString()));
        assertThat(outboxPayload.get("occurredAt")).isNotNull();

        rabbitAdmin.deleteQueue(queueName);
    }

    @Test
    void shouldLinkExistingPostToCollabOverwritePreviousCollabAndPublishEvent() throws Exception {
        String queueName = "test.post.collab.linked." + UUID.randomUUID();
        RabbitAdmin rabbitAdmin = new RabbitAdmin(connectionFactory);
        Queue queue = new Queue(queueName, false, true, true);
        rabbitAdmin.declareQueue(queue);
        rabbitAdmin.declareBinding(BindingBuilder.bind(queue)
                .to(new org.springframework.amqp.core.TopicExchange(rabbitMQProperties.getExchange().getPost().getEvents()))
                .with(rabbitMQProperties.getRk().getPost().getCollab().getLinked()));

        var previousCollab = seedCollab(ColabStatus.OPEN);
        var targetCollab = seedCollab(ColabStatus.OPEN);
        seedAcceptedAdminMember(targetCollab.getId(), CREATOR_ID, CollabMemberRole.ADMIN, CollabMemberStatus.ACCEPTED);
        var existingPost = seedPost(CREATOR_ID, "before", Set.of("alice"), Set.of("java"));
        existingPost.setCollabId(previousCollab.getId());
        existingPost.setPostType(PostType.COLAB);
        postJpaRepository.saveAndFlush(existingPost);

        mockMvc.perform(put("/api/posts/{postId}/collabs/{collabId}/link", existingPost.getId(), targetCollab.getId())
                        .with(jwtFor(CREATOR_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.postId").value(existingPost.getId().toString()))
                .andExpect(jsonPath("$.collabId").value(targetCollab.getId().toString()))
                .andExpect(jsonPath("$.postType").value("COLAB"));

        var updatedPost = postJpaRepository.findById(existingPost.getId()).orElseThrow();
        assertThat(updatedPost.getCollabId()).isEqualTo(targetCollab.getId());
        assertThat(updatedPost.getPostType()).isEqualTo(PostType.COLAB);
        assertThat(outboxEventRepository.findAll()).hasSize(1);

        Message message = receiveMessage(queueName);
        assertThat(message).isNotNull();
        var eventPayload = objectMapper.readValue(message.getBody(), new TypeReference<Map<String, Object>>() { });
        assertThat(eventPayload.get("postId")).isEqualTo(existingPost.getId().toString());
        assertThat(eventPayload.get("collabId")).isEqualTo(targetCollab.getId().toString());
        assertThat(eventPayload.get("postType")).isEqualTo("COLAB");

        rabbitAdmin.deleteQueue(queueName);
    }

    @Test
    void shouldReturnNotFoundWhenLinkingMissingPost() throws Exception {
        var targetCollab = seedCollab(ColabStatus.OPEN);
        seedAcceptedAdminMember(targetCollab.getId(), CREATOR_ID, CollabMemberRole.ADMIN, CollabMemberStatus.ACCEPTED);

        mockMvc.perform(put("/api/posts/{postId}/collabs/{collabId}/link", UUID.randomUUID(), targetCollab.getId())
                        .with(jwtFor(CREATOR_ID)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
    }

    @Test
    void shouldReturnNotFoundWhenLinkingToMissingCollab() throws Exception {
        var existingPost = seedPost(CREATOR_ID, "before", Set.of(), Set.of());

        mockMvc.perform(put("/api/posts/{postId}/collabs/{collabId}/link", existingPost.getId(), UUID.randomUUID())
                        .with(jwtFor(CREATOR_ID)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
    }

    @Test
    void shouldReturnForbiddenWhenRequesterOwnsPostButIsNotAcceptedAdminInCollab() throws Exception {
        var targetCollab = seedCollab(ColabStatus.OPEN);
        seedAcceptedAdminMember(targetCollab.getId(), CREATOR_ID, CollabMemberRole.MEMBER, CollabMemberStatus.ACCEPTED);
        var existingPost = seedPost(CREATOR_ID, "before", Set.of(), Set.of());

        mockMvc.perform(put("/api/posts/{postId}/collabs/{collabId}/link", existingPost.getId(), targetCollab.getId())
                        .with(jwtFor(CREATOR_ID)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
    }

    @Test
    void shouldReturnForbiddenWhenRequesterIsAcceptedAdminButNotPostOwner() throws Exception {
        var targetCollab = seedCollab(ColabStatus.OPEN);
        seedAcceptedAdminMember(targetCollab.getId(), CREATOR_ID, CollabMemberRole.ADMIN, CollabMemberStatus.ACCEPTED);
        var existingPost = seedPost(UUID.randomUUID(), "before", Set.of(), Set.of());

        mockMvc.perform(put("/api/posts/{postId}/collabs/{collabId}/link", existingPost.getId(), targetCollab.getId())
                        .with(jwtFor(CREATOR_ID)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
    }

    @Test
    void shouldReturnBadRequestWhenLinkingPostToClosedCollab() throws Exception {
        var targetCollab = seedCollab(ColabStatus.CLOSED);
        var existingPost = seedPost(CREATOR_ID, "before", Set.of(), Set.of());

        mockMvc.perform(put("/api/posts/{postId}/collabs/{collabId}/link", existingPost.getId(), targetCollab.getId())
                        .with(jwtFor(CREATOR_ID)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));
    }

    @Test
    void shouldReturnForbiddenWhenDeletingPostOwnedByAnotherUser() throws Exception {
        var existingPost = seedPost(UUID.randomUUID(), "before", Set.of(), Set.of());

        mockMvc.perform(delete("/api/posts/{postId}", existingPost.getId())
                        .with(jwtFor(CREATOR_ID)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
    }

    @Test
    void shouldReturnNotFoundWhenDeletingPostThatDoesNotExist() throws Exception {
        mockMvc.perform(delete("/api/posts/{postId}", UUID.randomUUID())
                        .with(jwtFor(CREATOR_ID)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
    }

    @Test
    void shouldReturnBadRequestWhenDeletingPostThatIsAlreadyDeleted() throws Exception {
        var existingPost = postJpaRepository.save(com.app.postcommandservice.post.infrastructure.entity.PostEntity.builder()
                .id(UUID.randomUUID())
                .userId(CREATOR_ID)
                .collabId(null)
                .postType(PostType.BASIC)
                .description("before")
                .taggedUsers(new ArrayList<>())
                .tags(new ArrayList<>())
                .status(PostStatus.DELETED)
                .build());

        mockMvc.perform(delete("/api/posts/{postId}", existingPost.getId())
                        .with(jwtFor(CREATOR_ID)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));
    }

    @Test
    void shouldReturnSamePostBodyWhenCalledTwiceWithTheSameCorrelationId() throws Exception {
        var correlationId = UUID.randomUUID();
        var payload = objectMapper.writeValueAsString(Map.of(
                "correlationId", correlationId,
                "description", "idempotent",
                "taggedUsers", Set.of(),
                "postTags", Set.of("java")
        ));

        var firstResponse = mockMvc.perform(post("/api/posts")
                        .with(jwtFor(CREATOR_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        var secondResponse = mockMvc.perform(post("/api/posts")
                        .with(jwtFor(CREATOR_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        var firstBody = objectMapper.readValue(firstResponse, new TypeReference<Map<String, Object>>() { });
        var secondBody = objectMapper.readValue(secondResponse, new TypeReference<Map<String, Object>>() { });

        assertPostBodiesEqualIgnoringTimestampPrecision(secondBody, firstBody);
        assertThat(postJpaRepository.count()).isEqualTo(1);
        assertThat(postRequestIdempotencyJpaRepository.count()).isEqualTo(1);
        assertThat(outboxEventRepository.count()).isEqualTo(1);
    }

    @Test
    void shouldReturnSamePostBodyWhenConcurrentRequestsReuseTheSameCorrelationId() throws Exception {
        var correlationId = UUID.randomUUID();
        var payload = objectMapper.writeValueAsString(Map.of(
                "correlationId", correlationId,
                "description", "concurrent",
                "taggedUsers", Set.of(),
                "postTags", Set.of("java")
        ));
        var readyLatch = new CountDownLatch(2);
        var startLatch = new CountDownLatch(1);

        try (ExecutorService executorService = Executors.newFixedThreadPool(2)) {
            List<Future<String>> futures = new ArrayList<>();
            for (int index = 0; index < 2; index++) {
                futures.add(executorService.submit(concurrentCreatePostRequest(payload, readyLatch, startLatch)));
            }

            readyLatch.await();
            startLatch.countDown();

            var firstBody = objectMapper.readValue(futures.get(0).get(), new TypeReference<Map<String, Object>>() { });
            var secondBody = objectMapper.readValue(futures.get(1).get(), new TypeReference<Map<String, Object>>() { });

            assertPostBodiesEqualIgnoringTimestampPrecision(firstBody, secondBody);
        }

        assertThat(postJpaRepository.count()).isEqualTo(1);
        assertThat(postRequestIdempotencyJpaRepository.count()).isEqualTo(1);
        assertThat(outboxEventRepository.count()).isEqualTo(1);
    }

    @Test
    void shouldReturnNotFoundWhenTaggedUsernameDoesNotExist() throws Exception {
        var payload = objectMapper.writeValueAsString(Map.of(
                "correlationId", UUID.randomUUID(),
                "description", "hello",
                "taggedUsers", Set.of("missing"),
                "postTags", Set.of()
        ));

        mockMvc.perform(post("/api/posts")
                        .with(jwtFor(CREATOR_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
    }

    @Test
    void shouldReturnForbiddenWhenTaggedUserIsBlocked() throws Exception {
        UUID aliceId = UUID.randomUUID();
        seedUser(aliceId, "alice");
        blockReadModelJpaRepository.save(new BlockReadModelEntity(
                new BlockReadModelId(CREATOR_ID, aliceId),
                Instant.now()
        ));

        var payload = objectMapper.writeValueAsString(Map.of(
                "correlationId", UUID.randomUUID(),
                "description", "hello",
                "taggedUsers", Set.of("alice"),
                "postTags", Set.of()
        ));

        mockMvc.perform(post("/api/posts")
                        .with(jwtFor(CREATOR_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("BLOCKED"));
    }

    private void seedUser(UUID userId, String username) {
        var now = Instant.now();
        userReadModelJpaRepository.save(new UserReadModelEntity(userId, username, now, now));
    }

    private com.app.postcommandservice.post.infrastructure.entity.PostEntity seedPost(
            UUID ownerId,
            String description,
            Set<String> taggedUsers,
            Set<String> tags) {
        return postJpaRepository.save(com.app.postcommandservice.post.infrastructure.entity.PostEntity.builder()
                .id(UUID.randomUUID())
                .userId(ownerId)
                .collabId(null)
                .postType(PostType.BASIC)
                .description(description)
                .taggedUsers(new ArrayList<>(taggedUsers))
                .tags(new ArrayList<>(tags))
                .status(PostStatus.ACTIVE)
                .build());
    }

    private CollabEntity seedCollab(UUID createdBy, String title) {
        return collabJpaRepository.save(new CollabEntity(
                UUID.randomUUID(),
                title,
                createdBy,
                ColabStatus.OPEN,
                null
        ));
    }

    private CollabEntity seedCollab(ColabStatus collabStatus) {
        return collabJpaRepository.save(new CollabEntity(
                UUID.randomUUID(),
                "Open collab",
                CREATOR_ID,
                collabStatus,
                null
        ));
    }

    private void seedAcceptedAdminMember(
            UUID collabId,
            UUID userId,
            CollabMemberRole role,
            CollabMemberStatus status) {
        collabMemberJpaRepository.save(new CollabMemberEntity(
                new CollabMemberId(collabId, userId),
                status,
                role,
                null
        ));
    }

    private Callable<String> concurrentCreatePostRequest(
            String payload,
            CountDownLatch readyLatch,
            CountDownLatch startLatch) {
        return () -> {
            readyLatch.countDown();
            startLatch.await();

            return mockMvc.perform(post("/api/posts")
                            .with(jwtFor(CREATOR_ID))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
        };
    }

    private SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor jwtFor(UUID userId) {
        return jwt().jwt(jwt -> jwt.subject(userId.toString()));
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

    private void assertPostBodiesEqualIgnoringTimestampPrecision(
            Map<String, Object> firstBody,
            Map<String, Object> secondBody) {
        assertThat(firstBody.get("postId")).isEqualTo(secondBody.get("postId"));
        assertThat(firstBody.get("userId")).isEqualTo(secondBody.get("userId"));
        assertThat(firstBody.get("collabId")).isEqualTo(secondBody.get("collabId"));
        assertThat(firstBody.get("postType")).isEqualTo(secondBody.get("postType"));
        assertThat(firstBody.get("description")).isEqualTo(secondBody.get("description"));
        assertThat(firstBody.get("taggedUsers")).isEqualTo(secondBody.get("taggedUsers"));
        assertThat(firstBody.get("postTags")).isEqualTo(secondBody.get("postTags"));
        assertTimestampsEquivalent(firstBody.get("createdAt"), secondBody.get("createdAt"));
        assertTimestampsEquivalent(firstBody.get("updatedAt"), secondBody.get("updatedAt"));
    }

    private Instant parseInstant(Object value) {
        return normalizeTimestamp(Instant.parse(String.valueOf(value)));
    }

    private Instant normalizeTimestamp(Instant value) {
        return value.truncatedTo(ChronoUnit.MICROS);
    }

    private void assertTimestampsEquivalent(Object firstValue, Object secondValue) {
        var firstTimestamp = parseInstant(firstValue);
        var secondTimestamp = parseInstant(secondValue);
        assertThat(Duration.between(firstTimestamp, secondTimestamp).abs())
                .isLessThanOrEqualTo(java.time.Duration.of(1, ChronoUnit.MICROS));
    }
}
