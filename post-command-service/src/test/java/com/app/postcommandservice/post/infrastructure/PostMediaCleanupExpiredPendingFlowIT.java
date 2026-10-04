package com.app.postcommandservice.post.infrastructure;

import java.io.ByteArrayInputStream;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.common.StorageSharedKeyCredential;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.app.postcommandservice.TestcontainersConfiguration;
import com.app.postcommandservice.post.application.usecase.PostMediaCleanupService;
import com.app.postcommandservice.post.domain.model.valueobj.MediaType;
import com.app.postcommandservice.post.domain.model.valueobj.PostStatus;
import com.app.postcommandservice.post.domain.model.valueobj.PostType;
import com.app.postcommandservice.post.infrastructure.entity.PostEntity;
import com.app.postcommandservice.post.infrastructure.entity.PostMediaEntity;
import com.app.postcommandservice.post.infrastructure.repository.PostJpaRepository;
import com.app.postcommandservice.shared.infrastructure.rabbitmq.config.RabbitMQProperties;
import com.app.postcommandservice.shared.infrastructure.repository.OutboxEventRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end coverage of the task-39 scheduled media-cleanup job's happy path against a real
 * Azurite emulator and this service's own real Postgres/RabbitMQ containers: {@code
 * PostMediaCleanupService.run()} is invoked directly (rather than waiting for its hourly cron)
 * to expire/purge a post whose media upload was never confirmed in time.
 *
 * <p>Deliberately its own file/Spring context (a dedicated {@code CONTAINER_NAME}, mirroring
 * the precedent set by {@code PostMediaVerificationFlowIT}/{@code OutageIT}/{@code
 * NoConnectionHeldIT} living in separate files): a shared context across multiple tests that
 * each call the ShedLock-guarded {@code run()} surfaced flaky cross-test Postgres statement
 * caching, so each cleanup-job scenario gets its own fully isolated context.</p>
 */
@Testcontainers
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class PostMediaCleanupExpiredPendingFlowIT {

    private static final UUID AUTHOR_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final String ACCOUNT_NAME = "devstoreaccount1";
    // Azurite's well-known default emulator key (local emulator only, never a real secret).
    private static final String ACCOUNT_KEY =
            "Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==";
    private static final String CONTAINER_NAME = "post-media-cleanup-expired-pending-it";

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
        registry.add("azure.storage.account-url", PostMediaCleanupExpiredPendingFlowIT::resolveAccountUrl);
        registry.add("azure.storage.container", () -> CONTAINER_NAME);
        registry.add("azure.storage.use-account-key", () -> true);
        registry.add("azure.storage.account-key", () -> ACCOUNT_KEY);
        registry.add("azure.storage.allow-http", () -> true);
    }

    @BeforeAll
    static void setUpContainer() {
        accountUrl = resolveAccountUrl();
        var blobServiceClient = new BlobServiceClientBuilder()
                .endpoint(accountUrl)
                .credential(new StorageSharedKeyCredential(ACCOUNT_NAME, ACCOUNT_KEY))
                .buildClient();
        blobContainerClient = blobServiceClient.getBlobContainerClient(CONTAINER_NAME);
        blobContainerClient.createIfNotExists();
    }

    @AfterAll
    static void tearDownContainer() {
        if (blobContainerClient != null) {
            blobContainerClient.deleteIfExists();
        }
    }

    @Autowired
    private PostMediaCleanupService postMediaCleanupService;

    @Autowired
    private PostJpaRepository postJpaRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private ConnectionFactory connectionFactory;

    @Autowired
    private RabbitMQProperties rabbitMQProperties;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @AfterEach
    void tearDown() {
        postJpaRepository.deleteAll();
        outboxEventRepository.deleteAll();
        jdbcTemplate.update("DELETE FROM shedlock");
    }

    private String uploadBlob() {
        String blobName = UUID.randomUUID().toString();
        byte[] content = "fake-media-bytes".getBytes();
        blobContainerClient.getBlobClient(blobName).upload(new ByteArrayInputStream(content), content.length, true);
        return accountUrl + "/" + CONTAINER_NAME + "/" + blobName;
    }

    private PostEntity seedPost(UUID mediaId, String blobUrl, PostStatus status, Instant createdAt) {
        var postEntity = PostEntity.builder()
                .id(UUID.randomUUID())
                .userId(AUTHOR_ID)
                .postType(PostType.BASIC)
                .description("cleanup-job post")
                .tags(List.of("test"))
                .status(status)
                .build();
        postEntity.addMedia(PostMediaEntity.builder()
                .id(mediaId)
                .url(blobUrl)
                .thumbnailUrl(blobUrl)
                .mediaType(MediaType.IMAGE)
                .mediaOrder(1)
                .build());
        var saved = postJpaRepository.save(postEntity);
        // @PrePersist always stamps createdAt = now(); backdate it directly so the job sees an
        // expired post without waiting 48 real hours.
        jdbcTemplate.update("UPDATE posts SET created_at = ? WHERE id = ?", Timestamp.from(createdAt), saved.getId());
        return saved;
    }

    private boolean blobExists(String blobUrl) {
        String blobName = blobUrl.substring(blobUrl.lastIndexOf('/') + 1);
        return blobContainerClient.getBlobClient(blobName).exists();
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

    private Message receiveMessage(String queueName) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 8000;
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

    @Test
    void shouldFailExpiredPendingPostDeleteItsBlobsAndSetMediaPurgedAt() throws Exception {
        var mediaId = UUID.randomUUID();
        var blobUrl = uploadBlob();
        var post = seedPost(mediaId, blobUrl, PostStatus.PENDING, Instant.now().minus(Duration.ofHours(49)));

        var failedQueue = bindTestQueue(rabbitMQProperties.getRk().getPost().getMedia().getValidation().getFailed());

        postMediaCleanupService.run();

        var reloaded = postJpaRepository.findById(post.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PostStatus.MEDIA_UPLOAD_FAILED);
        assertThat(reloaded.getMediaPurgedAt()).isNotNull();
        assertThat(blobExists(blobUrl)).isFalse();

        var failedMessage = receiveMessage(failedQueue);
        assertThat(failedMessage).isNotNull();
        var payload = objectMapper.readValue(failedMessage.getBody(), new TypeReference<java.util.Map<String, Object>>() { });
        assertThat(payload.get("postId")).isEqualTo(post.getId().toString());
        assertThat(payload.get("reasonCode")).isEqualTo("UPLOAD_EXPIRED");

        deleteQueue(failedQueue);
    }
}
