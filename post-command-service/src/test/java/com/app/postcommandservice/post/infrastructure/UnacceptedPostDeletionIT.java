package com.app.postcommandservice.post.infrastructure;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.common.StorageSharedKeyCredential;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.app.postcommandservice.TestcontainersConfiguration;
import com.app.postcommandservice.collab.infrastructure.repository.CollabJpaRepository;
import com.app.postcommandservice.collab.infrastructure.repository.CollabMemberJpaRepository;
import com.app.postcommandservice.collab.infrastructure.repository.CollabRequestIdempotencyJpaRepository;
import com.app.postcommandservice.post.application.commands.DeletePostCommand;
import com.app.postcommandservice.post.application.repository.PostRepository;
import com.app.postcommandservice.post.application.usecase.DeletePostUseCase;
import com.app.postcommandservice.post.application.usecase.DeleteUnacceptedPostUseCase;
import com.app.postcommandservice.post.domain.exception.PostNotFoundException;
import com.app.postcommandservice.post.infrastructure.events.PostDeletedEvent;
import com.app.postcommandservice.post.infrastructure.mapper.PostEventMapper;
import com.app.postcommandservice.shared.infrastructure.mapper.JsonMapper;
import com.app.postcommandservice.post.application.usecase.FailedPostPurgeService;
import com.app.postcommandservice.post.domain.model.valueobj.PostStatus;
import com.app.postcommandservice.post.domain.model.valueobj.PostType;
import com.app.postcommandservice.post.infrastructure.config.FailedPostPurgeProperties;
import com.app.postcommandservice.post.infrastructure.entity.PostEntity;
import com.app.postcommandservice.post.infrastructure.entity.PostMediaEntity;
import com.app.postcommandservice.post.infrastructure.events.PostMediaUploadedEvent;
import com.app.postcommandservice.post.infrastructure.events.PostMediaUploadedMediaPayload;
import com.app.postcommandservice.post.infrastructure.repository.PostJpaRepository;
import com.app.postcommandservice.post.infrastructure.repository.PostRequestIdempotencyJpaRepository;
import com.app.postcommandservice.shared.infrastructure.rabbitmq.config.RabbitMQProperties;
import com.app.postcommandservice.shared.infrastructure.repository.OutboxEventRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Deletion of never-accepted posts (task 60) against real Postgres, RabbitMQ and Azurite: the
 * owner's {@code DELETE /api/posts/{postId}} for {@code PENDING} / {@code MEDIA_UPLOAD_FAILED}
 * posts, the scheduled purge of failed posts (triggered by calling
 * {@link FailedPostPurgeService#run()}; the cron is disabled in the test profile) and a late
 * media-uploaded message for a deleted post. The context is closed after the class to release its
 * containers.
 */
@Testcontainers
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
class UnacceptedPostDeletionIT {

    private static final UUID OWNER_ID = UUID.fromString("60606060-6060-6060-6060-606060606060");
    private static final String ACCOUNT_NAME = "devstoreaccount1";
    // Azurite's well-known default emulator key (local emulator only, never a real secret).
    private static final String ACCOUNT_KEY =
            "Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==";
    private static final String CONTAINER_NAME = "unaccepted-post-deletion-it";
    private static final byte[] CONTENT = {1, 2, 3, 4};

    @Container
    private static final GenericContainer<?> AZURITE = new GenericContainer<>(
            DockerImageName.parse("mcr.microsoft.com/azure-storage/azurite:latest"))
            .withExposedPorts(10000)
            .waitingFor(Wait.forLogMessage(".*Azurite Blob service is successfully listening.*\\n", 1));

    private static BlobContainerClient blobContainerClient;
    private static String accountUrl;

    private static String resolveAccountUrl() {
        return "http://" + AZURITE.getHost() + ":" + AZURITE.getMappedPort(10000) + "/" + ACCOUNT_NAME;
    }

    @DynamicPropertySource
    static void azureProperties(DynamicPropertyRegistry registry) {
        registry.add("azure.storage.account-url", UnacceptedPostDeletionIT::resolveAccountUrl);
        registry.add("azure.storage.container", () -> CONTAINER_NAME);
        registry.add("azure.storage.use-account-key", () -> true);
        registry.add("azure.storage.account-key", () -> ACCOUNT_KEY);
        registry.add("azure.storage.allow-http", () -> true);
    }

    @BeforeAll
    static void setUpContainer() {
        accountUrl = resolveAccountUrl();
        blobContainerClient = new BlobServiceClientBuilder()
                .endpoint(accountUrl)
                .credential(new StorageSharedKeyCredential(ACCOUNT_NAME, ACCOUNT_KEY))
                .buildClient()
                .getBlobContainerClient(CONTAINER_NAME);
        blobContainerClient.createIfNotExists();
    }

    @AfterAll
    static void tearDownContainer() {
        if (blobContainerClient != null) {
            blobContainerClient.deleteIfExists();
        }
    }

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private FailedPostPurgeService failedPostPurgeService;

    @Autowired
    private FailedPostPurgeProperties failedPostPurgeProperties;

    @Autowired
    private DeleteUnacceptedPostUseCase deleteUnacceptedPostUseCase;

    @Autowired
    private PostJpaRepository postJpaRepository;

    @Autowired
    private PostRequestIdempotencyJpaRepository postRequestIdempotencyJpaRepository;

    @Autowired
    private CollabJpaRepository collabJpaRepository;

    @Autowired
    private CollabMemberJpaRepository collabMemberJpaRepository;

    @Autowired
    private CollabRequestIdempotencyJpaRepository collabRequestIdempotencyJpaRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private ConnectionFactory connectionFactory;

    @Autowired
    private RabbitMQProperties rabbitMQProperties;

    @Autowired
    private PostRepository postRepository;

    @Autowired
    private PostEventMapper postEventMapper;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private ApplicationEventPublisher applicationEventPublisher;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private MockMvc mockMvc;

    /** Releases a job lock left by a previous test (rows are updated, never deleted: see PostMediaCleanupJobIT). */
    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
        jdbcTemplate.update(
                "UPDATE shedlock SET lock_until = timezone('utc', CURRENT_TIMESTAMP) - INTERVAL '1 second'");
    }

    @AfterEach
    void tearDown() {
        postRequestIdempotencyJpaRepository.deleteAll();
        postJpaRepository.deleteAll();
        collabMemberJpaRepository.deleteAll();
        collabRequestIdempotencyJpaRepository.deleteAll();
        collabJpaRepository.deleteAll();
        outboxEventRepository.deleteAll();
    }

    @Test
    void shouldDeleteTheOwnersMediaUploadFailedPostWithItsRowsAndBlobsAndPublishNoPostEvent() throws Exception {
        var imageUrl = uploadBlob();
        var failed = seedPost(PostStatus.MEDIA_UPLOAD_FAILED, imageUrl, imageUrl);
        seedPostRequestIdempotency(failed.getId());
        var deletedQueue = bindTestQueue(rabbitMQProperties.getRk().getPost().getDeleted());

        mockMvc.perform(delete("/api/posts/{postId}", failed.getId()).with(jwtFor(OWNER_ID)))
                .andExpect(status().isNoContent());

        assertThat(postJpaRepository.existsById(failed.getId())).isFalse();
        assertThat(countRows("post_media", "post_id", failed.getId())).isZero();
        assertThat(countRows("post_tags", "post_id", failed.getId())).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM post_media_tagged_users", Long.class)).isZero();
        assertThat(countRows("post_request_idempotency", "post_id", failed.getId())).isZero();
        assertThat(blobExists(imageUrl)).isFalse();
        assertThat(outboxEventRepository.count()).isZero();
        assertThat(receiveMessage(deletedQueue, 1500)).isNull();
        deleteQueue(deletedQueue);
    }

    @Test
    void shouldDeleteTheOwnersPendingPostSoThatARetriedCreateStartsANewPost() throws Exception {
        var correlationId = UUID.randomUUID();
        var createPayload = objectMapper.writeValueAsString(Map.of(
                "correlationId", correlationId,
                "description", "pending post",
                "postTags", Set.of("pending"),
                "media", List.of(Map.of("mediaType", "VIDEO", "order", 1))));
        var postId = createPost(createPayload, 201);
        assertThat(countRows("post_request_idempotency", "post_id", postId)).isEqualTo(1);

        mockMvc.perform(delete("/api/posts/{postId}", postId).with(jwtFor(OWNER_ID)))
                .andExpect(status().isNoContent());

        assertThat(postJpaRepository.existsById(postId)).isFalse();
        assertThat(countRows("post_media", "post_id", postId)).isZero();
        assertThat(countRows("post_request_idempotency", "post_id", postId)).isZero();
        assertThat(outboxEventRepository.count()).isZero();
        // No dangling idempotency row: the same correlation id creates a new post instead of replaying
        assertThat(createPost(createPayload, 201)).isNotEqualTo(postId);
    }

    @Test
    void shouldReturnForbiddenWhenAnotherUserDeletesAPendingPost() throws Exception {
        var blobUrl = uploadBlob();
        var pending = seedPost(PostStatus.PENDING, blobUrl, blobUrl);

        mockMvc.perform(delete("/api/posts/{postId}", pending.getId()).with(jwtFor(UUID.randomUUID())))
                .andExpect(status().isForbidden());

        assertThat(postJpaRepository.findById(pending.getId()).orElseThrow().getStatus()).isEqualTo(PostStatus.PENDING);
        assertThat(blobExists(blobUrl)).isTrue();
    }

    @Test
    void shouldDeleteTheCollabCreatedWithThePendingPostAndPublishOneCollabDeletedEvent() throws Exception {
        var collabDeletedQueue = bindTestQueue(rabbitMQProperties.getRk().getPost().getCollab().getDeleted());
        var payload = objectMapper.writeValueAsString(Map.of(
                "correlationId", UUID.randomUUID(),
                "title", "Summer collab",
                "description", "collab post",
                "postTags", Set.of("collab"),
                "media", List.of(Map.of("mediaType", "IMAGE", "order", 1))));
        var response = mockMvc.perform(post("/api/collabs")
                        .with(jwtFor(OWNER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        Map<String, Object> body = objectMapper.readValue(response, new TypeReference<>() { });
        var collabId = UUID.fromString((String) body.get("collabId"));
        @SuppressWarnings("unchecked")
        var postId = UUID.fromString((String) ((Map<String, Object>) body.get("post")).get("postId"));
        assertThat(collabMemberJpaRepository.count()).isEqualTo(1);
        assertThat(collabRequestIdempotencyJpaRepository.count()).isEqualTo(1);

        mockMvc.perform(delete("/api/posts/{postId}", postId).with(jwtFor(OWNER_ID)))
                .andExpect(status().isNoContent());

        assertThat(postJpaRepository.existsById(postId)).isFalse();
        assertThat(collabJpaRepository.existsById(collabId)).isFalse();
        assertThat(collabMemberJpaRepository.count()).isZero();
        assertThat(collabRequestIdempotencyJpaRepository.count()).isZero();

        var message = receiveMessage(collabDeletedQueue, 8000);
        assertThat(message).isNotNull();
        Map<String, Object> event = objectMapper.readValue(message.getBody(), new TypeReference<>() { });
        assertThat(event.get("collabId")).isEqualTo(collabId.toString());
        assertThat(event.get("actionedBy")).isEqualTo(OWNER_ID.toString());
        assertThat(receiveMessage(collabDeletedQueue, 1500)).isNull();
        deleteQueue(collabDeletedQueue);
    }

    @Test
    void shouldNeverDeleteAnAcceptedPostThroughTheUnacceptedPath() {
        var blobUrl = uploadBlob();
        var accepted = seedPost(PostStatus.ACCEPTED, blobUrl, blobUrl);

        var outcome = deleteUnacceptedPostUseCase.delete(accepted.getId());

        assertThat(outcome.postDeleted()).isFalse();
        assertThat(postJpaRepository.findById(accepted.getId()).orElseThrow().getStatus()).isEqualTo(PostStatus.ACCEPTED);
        assertThat(blobExists(blobUrl)).isTrue();
    }

    @Test
    void shouldPurgeFailedPostsOlderThanTheRetentionInBoundedBatches() {
        var old = List.of(
                seedPurgedFailedPost(Duration.ofDays(8)),
                seedPurgedFailedPost(Duration.ofDays(9)),
                seedPurgedFailedPost(Duration.ofDays(10)));
        var recent = seedPurgedFailedPost(Duration.ofDays(2));
        var notPurged = seedPost(PostStatus.MEDIA_UPLOAD_FAILED, "https://blob/never-purged.jpg", "https://blob/never-purged.jpg");
        var acceptedUrl = "https://blob/accepted.jpg";
        var accepted = seedPost(PostStatus.ACCEPTED, acceptedUrl, acceptedUrl);
        setMediaPurgedAt(accepted.getId(), Duration.ofDays(30));

        int batchSize = failedPostPurgeProperties.getBatchSize();
        int maxBatches = failedPostPurgeProperties.getMaxBatchesPerRun();
        failedPostPurgeProperties.setBatchSize(1);
        failedPostPurgeProperties.setMaxBatchesPerRun(2);
        try {
            failedPostPurgeService.run();

            // One run handles at most batch-size * max-batches-per-run posts, oldest purge first
            assertThat(old.stream().filter(post -> postJpaRepository.existsById(post.getId())).toList())
                    .singleElement()
                    .satisfies(left -> assertThat(left.getId()).isEqualTo(old.getFirst().getId()));

            failedPostPurgeService.run();
        } finally {
            failedPostPurgeProperties.setBatchSize(batchSize);
            failedPostPurgeProperties.setMaxBatchesPerRun(maxBatches);
        }

        assertThat(old).noneMatch(post -> postJpaRepository.existsById(post.getId()));
        assertThat(postJpaRepository.existsById(recent.getId())).isTrue();
        assertThat(postJpaRepository.existsById(notPurged.getId())).isTrue();
        assertThat(postJpaRepository.existsById(accepted.getId())).isTrue();
        assertThat(outboxEventRepository.count()).isZero();
    }

    @Test
    void shouldAckALateMediaUploadedMessageForADeletedPost(CapturedOutput output) throws Exception {
        var blobUrl = uploadBlob();
        var pending = seedPost(PostStatus.PENDING, blobUrl, blobUrl);
        var mediaId = pending.getMedia().getFirst().getId();
        mockMvc.perform(delete("/api/posts/{postId}", pending.getId()).with(jwtFor(OWNER_ID)))
                .andExpect(status().isNoContent());

        rabbitTemplate.convertAndSend(
                rabbitMQProperties.getExchange().getPost().getEvents(),
                rabbitMQProperties.getRk().getPost().getMedia().getUploaded(),
                PostMediaUploadedEvent.builder()
                        .id(UUID.randomUUID())
                        .correlationId(UUID.randomUUID())
                        .occurredAt(Instant.now())
                        .postId(pending.getId())
                        .media(List.of(PostMediaUploadedMediaPayload.builder()
                                .id(mediaId)
                                .url(blobUrl)
                                .thumbnailUrl(blobUrl)
                                .mediaType(com.app.postcommandservice.post.domain.model.valueobj.MediaType.IMAGE)
                                .order(1)
                                .build()))
                        .build());

        var expectedLog = "Ignoring media-uploaded event for post " + pending.getId() + ": the post no longer exists";
        waitUntil(() -> output.getOut().contains(expectedLog));
        var admin = new RabbitAdmin(connectionFactory);
        var mediaQueue = rabbitMQProperties.getQueue().getPostMedia();
        waitUntil(() -> messageCount(admin, mediaQueue) == 0);
        assertThat(messageCount(admin, mediaQueue + ".dlq")).isZero();
        assertThat(output.getOut()).containsOnlyOnce(expectedLog);
        assertThat(outboxEventRepository.count()).isZero();
    }

    /**
     * Media verification commits ACCEPTED after DeletePostUseCase loaded the post as PENDING (so the
     * PENDING entity sits in its persistence context) and before the unaccepted delete reads it.
     */
    @Test
    void shouldApplyTheAcceptedRulesWhenThePostIsAcceptedAfterTheOwnerRequestReadIt() {
        var blobUrl = uploadBlob();
        var pending = seedPost(PostStatus.PENDING, blobUrl, blobUrl);
        var deletePostUseCase = deletePostUseCaseRacingWith(() -> jdbcTemplate.update(
                "UPDATE posts SET status = 'ACCEPTED' WHERE id = ?", pending.getId()));

        inTransaction(() -> deletePostUseCase.deletePost(new DeletePostCommand(pending.getId(), OWNER_ID)));

        assertThat(postJpaRepository.findById(pending.getId()).orElseThrow().getStatus()).isEqualTo(PostStatus.DELETED);
        assertThat(outboxEventRepository.findAll())
                .singleElement()
                .satisfies(event -> assertThat(event.getEventType()).isEqualTo(PostDeletedEvent.class.getSimpleName()));
        assertThat(blobExists(blobUrl)).isTrue();
    }

    /** A concurrent delete removes the post after DeletePostUseCase loaded it: 404, not 400. */
    @Test
    void shouldAnswerNotFoundWhenThePostIsDeletedConcurrentlyAfterTheOwnerRequestReadIt() {
        var blobUrl = uploadBlob();
        var pending = seedPost(PostStatus.PENDING, blobUrl, blobUrl);
        var deletePostUseCase = deletePostUseCaseRacingWith(() -> jdbcTemplate.update(
                "DELETE FROM posts WHERE id = ?", pending.getId()));

        assertThatThrownBy(() -> inTransaction(
                () -> deletePostUseCase.deletePost(new DeletePostCommand(pending.getId(), OWNER_ID))))
                .isInstanceOf(PostNotFoundException.class);
        assertThat(outboxEventRepository.count()).isZero();
    }

    /**
     * The real DeletePostUseCase wiring, except that {@code concurrentChange} is committed in its own
     * transaction right before the unaccepted delete runs (after the use case loaded the post).
     */
    private DeletePostUseCase deletePostUseCaseRacingWith(Runnable concurrentChange) {
        var requiresNew = new TransactionTemplate(transactionManager);
        requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        var racingDelete = new DeleteUnacceptedPostUseCase(null, null, null, null, null, null) {
            @Override
            public Outcome delete(UUID postId) {
                requiresNew.executeWithoutResult(status -> concurrentChange.run());
                return deleteUnacceptedPostUseCase.delete(postId);
            }
        };
        return new DeletePostUseCase(
                postRepository, outboxEventRepository, postEventMapper, jsonMapper, applicationEventPublisher, racingDelete);
    }

    private void inTransaction(Runnable action) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> action.run());
    }

    private UUID createPost(String payload, int expectedStatus) throws Exception {
        var response = mockMvc.perform(post("/api/posts")
                        .with(jwtFor(OWNER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        Map<String, Object> body = objectMapper.readValue(response, new TypeReference<>() { });
        return UUID.fromString((String) body.get("postId"));
    }

    private PostEntity seedPost(PostStatus status, String url, String thumbnailUrl) {
        var postEntity = PostEntity.builder()
                .id(UUID.randomUUID())
                .userId(OWNER_ID)
                .postType(PostType.BASIC)
                .description("never accepted")
                .tags(new java.util.ArrayList<>(List.of("tag-a", "tag-b")))
                .status(status)
                .build();
        postEntity.addMedia(PostMediaEntity.builder()
                .id(UUID.randomUUID())
                .url(url)
                .thumbnailUrl(thumbnailUrl)
                .mediaType(com.app.postcommandservice.post.domain.model.valueobj.MediaType.IMAGE)
                .taggedUsers(new java.util.ArrayList<>(List.of("alice")))
                .mediaOrder(1)
                .build());
        return postJpaRepository.save(postEntity);
    }

    private PostEntity seedPurgedFailedPost(Duration purgedAgo) {
        var post = seedPost(PostStatus.MEDIA_UPLOAD_FAILED, "https://blob/purged.jpg", "https://blob/purged.jpg");
        setMediaPurgedAt(post.getId(), purgedAgo);
        return post;
    }

    private void setMediaPurgedAt(UUID postId, Duration purgedAgo) {
        jdbcTemplate.update("UPDATE posts SET media_purged_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minus(purgedAgo)), postId);
    }

    private void seedPostRequestIdempotency(UUID postId) {
        jdbcTemplate.update("""
                INSERT INTO post_request_idempotency (user_id, correlation_id, post_id, request_hash, created_at)
                VALUES (?, ?, ?, NULL, now())
                """, OWNER_ID, UUID.randomUUID(), postId);
    }

    private long countRows(String table, String column, UUID id) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?", Long.class, id);
    }

    private String uploadBlob() {
        String blobName = UUID.randomUUID().toString();
        blobContainerClient.getBlobClient(blobName).upload(new ByteArrayInputStream(CONTENT), CONTENT.length, true);
        return accountUrl + "/" + CONTAINER_NAME + "/" + blobName;
    }

    private boolean blobExists(String blobUrl) {
        return blobContainerClient.getBlobClient(blobUrl.substring(blobUrl.lastIndexOf('/') + 1)).exists();
    }

    private String bindTestQueue(String routingKey) {
        String queueName = "test." + routingKey.replace('.', '-') + "." + UUID.randomUUID();
        var rabbitAdmin = new RabbitAdmin(connectionFactory);
        Queue queue = new Queue(queueName, false, true, true);
        rabbitAdmin.declareQueue(queue);
        rabbitAdmin.declareBinding(BindingBuilder.bind(queue)
                .to(new TopicExchange(rabbitMQProperties.getExchange().getPost().getEvents()))
                .with(routingKey));
        return queueName;
    }

    private void deleteQueue(String queueName) {
        new RabbitAdmin(connectionFactory).deleteQueue(queueName);
    }

    private static long messageCount(RabbitAdmin admin, String queueName) {
        var info = admin.getQueueInfo(queueName);
        return info == null ? 0 : info.getMessageCount();
    }

    /** Polls with basic.get (no consumer, so the auto-delete queue survives between polls). */
    private Message receiveMessage(String queueName, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        do {
            Message message = rabbitTemplate.receive(queueName);
            if (message != null) {
                return message;
            }
            Thread.sleep(200L);
        } while (System.currentTimeMillis() < deadline);
        return null;
    }

    private static void waitUntil(Check condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.isMet()) {
                return;
            }
            Thread.sleep(200L);
        }
        throw new AssertionError("Condition was not met within timeout");
    }

    @FunctionalInterface
    private interface Check {
        boolean isMet();
    }

    private SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor jwtFor(UUID userId) {
        return jwt().jwt(jwt -> jwt.subject(userId.toString()));
    }
}
