package com.app.postcommandservice.post.infrastructure;

import java.io.ByteArrayInputStream;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;

import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.common.StorageSharedKeyCredential;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
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
import com.app.postcommandservice.shared.infrastructure.shedlock.ShedLockConfig;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Scheduled cleanup of expired uploads (task 58) against real Postgres, RabbitMQ and Azurite:
 * the job is triggered by calling {@link PostMediaCleanupService#run()} on the Spring proxy (the
 * cron is disabled in the test profile), so ShedLock and the transaction boundaries are the real
 * ones. The context is closed after the class to release its containers.
 */
@Testcontainers
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PostMediaCleanupJobIT {

    private static final UUID AUTHOR_ID = UUID.fromString("58585858-5858-5858-5858-585858585858");
    private static final String ACCOUNT_NAME = "devstoreaccount1";
    // Azurite's well-known default emulator key (local emulator only, never a real secret).
    private static final String ACCOUNT_KEY =
            "Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==";
    private static final String CONTAINER_NAME = "post-media-cleanup-job-it";
    private static final byte[] CONTENT = {1, 2, 3, 4};
    private static final Duration EXPIRED_AGE = Duration.ofHours(49);

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
        registry.add("azure.storage.account-url", PostMediaCleanupJobIT::resolveAccountUrl);
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
    private PostMediaCleanupService postMediaCleanupService;

    @Autowired
    private PostJpaRepository postJpaRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private ConnectionFactory connectionFactory;

    @Autowired
    private RabbitMQProperties rabbitMQProperties;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Releases any job lock left by a previous test. The rows must not be deleted: the JDBC lock
     * provider remembers which lock rows it already inserted and from then on only UPDATEs them,
     * so a deleted row would make every later run silently skip.
     */
    @BeforeEach
    void releaseLocks() {
        jdbcTemplate.update(
                "UPDATE shedlock SET lock_until = timezone('utc', CURRENT_TIMESTAMP) - INTERVAL '1 second'");
    }

    @AfterEach
    void tearDown() {
        postJpaRepository.deleteAll();
        outboxEventRepository.deleteAll();
    }

    @Test
    void shouldExpireAPendingPostDeleteItsBlobsAndPublishUploadExpired() throws Exception {
        var videoUrl = uploadBlob();
        var thumbnailUrl = uploadBlob();
        var pending = seedPost(PostStatus.PENDING, MediaType.VIDEO, videoUrl, thumbnailUrl, EXPIRED_AGE);
        var imageUrl = uploadBlob();
        var alreadyFailed = seedPost(PostStatus.MEDIA_UPLOAD_FAILED, MediaType.IMAGE, imageUrl, imageUrl, EXPIRED_AGE);
        var failedQueue = bindTestQueue(rabbitMQProperties.getRk().getPost().getMedia().getValidation().getFailed());

        postMediaCleanupService.run();

        var expired = postJpaRepository.findById(pending.getId()).orElseThrow();
        assertThat(expired.getStatus()).isEqualTo(PostStatus.MEDIA_UPLOAD_FAILED);
        assertThat(expired.getMediaPurgedAt()).isNotNull();
        var purged = postJpaRepository.findById(alreadyFailed.getId()).orElseThrow();
        assertThat(purged.getStatus()).isEqualTo(PostStatus.MEDIA_UPLOAD_FAILED);
        assertThat(purged.getMediaPurgedAt()).isNotNull();
        assertThat(blobExists(videoUrl)).isFalse();
        assertThat(blobExists(thumbnailUrl)).isFalse();
        assertThat(blobExists(imageUrl)).isFalse();

        // Exactly one failed event: for the expired PENDING post, none for the already failed one
        var message = receiveMessage(failedQueue, 8000);
        assertThat(message).isNotNull();
        Map<String, Object> payload = objectMapper.readValue(message.getBody(), new TypeReference<>() { });
        assertThat(payload.get("postId")).isEqualTo(pending.getId().toString());
        assertThat(payload.get("reasonCode")).isEqualTo("UPLOAD_EXPIRED");
        assertThat((String) payload.get("reason"))
                .isEqualTo("Upload of post " + pending.getId() + " expired: media was not confirmed within 48 hours");
        assertThat(receiveMessage(failedQueue, 1500)).isNull();

        // A second run finds nothing left to do
        postMediaCleanupService.run();
        assertThat(receiveMessage(failedQueue, 1500)).isNull();
        deleteQueue(failedQueue);
    }

    @Test
    void shouldLeaveAcceptedAndRecentPostsUntouched() {
        var acceptedUrl = uploadBlob();
        var accepted = seedPost(PostStatus.ACCEPTED, MediaType.IMAGE, acceptedUrl, acceptedUrl, EXPIRED_AGE);
        var recentUrl = uploadBlob();
        var recent = seedPost(PostStatus.PENDING, MediaType.IMAGE, recentUrl, recentUrl, Duration.ofHours(1));
        var recentFailedUrl = uploadBlob();
        var recentFailed = seedPost(
                PostStatus.MEDIA_UPLOAD_FAILED, MediaType.IMAGE, recentFailedUrl, recentFailedUrl, Duration.ofHours(1));

        postMediaCleanupService.run();

        assertUntouched(accepted, PostStatus.ACCEPTED, acceptedUrl);
        assertUntouched(recent, PostStatus.PENDING, recentUrl);
        assertUntouched(recentFailed, PostStatus.MEDIA_UPLOAD_FAILED, recentFailedUrl);
        assertThat(outboxEventRepository.count()).isZero();
    }

    @Test
    void shouldSkipTheRunWhileAnotherApplicationContextHoldsTheLock() throws Exception {
        var blobUrl = uploadBlob();
        var pending = seedPost(PostStatus.PENDING, MediaType.IMAGE, blobUrl, blobUrl, EXPIRED_AGE);

        // A second application context ("another instance") on the same database holds the job lock
        try (var otherInstance = new AnnotationConfigApplicationContext()) {
            otherInstance.registerBean(DataSource.class, () -> dataSource);
            otherInstance.register(ShedLockConfig.class, LockHoldingJob.class);
            otherInstance.refresh();
            var job = otherInstance.getBean(LockHoldingJob.class);
            var lockAcquired = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var holder = CompletableFuture.runAsync(() -> job.holdLock(lockAcquired, release));
            assertThat(lockAcquired.await(10, TimeUnit.SECONDS)).isTrue();

            postMediaCleanupService.run();

            assertThat(postJpaRepository.findById(pending.getId()).orElseThrow().getStatus())
                    .isEqualTo(PostStatus.PENDING);
            assertThat(blobExists(blobUrl)).isTrue();

            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
        }

        // Once the other instance released the lock, this instance runs the job
        postMediaCleanupService.run();

        var expired = postJpaRepository.findById(pending.getId()).orElseThrow();
        assertThat(expired.getStatus()).isEqualTo(PostStatus.MEDIA_UPLOAD_FAILED);
        assertThat(expired.getMediaPurgedAt()).isNotNull();
        assertThat(blobExists(blobUrl)).isFalse();
    }

    /** Stand-in for the cleanup job of another instance: same lock name, blocks until released. */
    public static class LockHoldingJob {

        @SchedulerLock(name = "postMediaCleanupJob", lockAtMostFor = "PT2M")
        public void holdLock(CountDownLatch lockAcquired, CountDownLatch release) {
            lockAcquired.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void assertUntouched(PostEntity seeded, PostStatus status, String blobUrl) {
        var post = postJpaRepository.findById(seeded.getId()).orElseThrow();
        assertThat(post.getStatus()).isEqualTo(status);
        assertThat(post.getMediaPurgedAt()).isNull();
        assertThat(blobExists(blobUrl)).isTrue();
    }

    private PostEntity seedPost(PostStatus status, MediaType mediaType, String url, String thumbnailUrl, Duration age) {
        var postEntity = PostEntity.builder()
                .id(UUID.randomUUID())
                .userId(AUTHOR_ID)
                .postType(PostType.BASIC)
                .description("cleanup post")
                .tags(List.of("test"))
                .status(status)
                .build();
        postEntity.addMedia(PostMediaEntity.builder()
                .id(UUID.randomUUID())
                .url(url)
                .thumbnailUrl(thumbnailUrl)
                .mediaType(mediaType)
                .mediaOrder(1)
                .build());
        var saved = postJpaRepository.save(postEntity);
        // createdAt is set by @PrePersist, so age the row afterwards
        jdbcTemplate.update("UPDATE posts SET created_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minus(age)), saved.getId());
        return saved;
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
}
