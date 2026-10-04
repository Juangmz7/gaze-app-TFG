package com.app.postcommandservice.post.infrastructure;

import java.io.ByteArrayInputStream;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.common.StorageSharedKeyCredential;

import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
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
import com.app.postcommandservice.shared.infrastructure.repository.OutboxEventRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves ShedLock (task 39) prevents two concurrent application instances from running the
 * media-cleanup job at the same time, against this service's own real Postgres container. See
 * {@code PostMediaCleanupExpiredPendingFlowIT} for why this lives in its own file/Spring
 * context.
 *
 * <p>A second application instance holding the same named lock is simulated by acquiring it
 * directly through the shared JDBC-backed {@link LockProvider} bean — exactly what {@code
 * @SchedulerLock}'s AOP interceptor does internally before invoking {@code
 * PostMediaCleanupService.run()} — rather than racing two real threads/contexts against each
 * other, which would otherwise be inherently flaky to assert on deterministically.</p>
 */
@Testcontainers
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class PostMediaCleanupShedLockIT {

    private static final String LOCK_NAME = "postMediaCleanupJob";
    private static final UUID AUTHOR_ID = UUID.fromString("66666666-6666-6666-6666-666666666666");
    private static final String ACCOUNT_NAME = "devstoreaccount1";
    // Azurite's well-known default emulator key (local emulator only, never a real secret).
    private static final String ACCOUNT_KEY =
            "Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==";
    private static final String CONTAINER_NAME = "post-media-cleanup-shedlock-it";

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
        registry.add("azure.storage.account-url", PostMediaCleanupShedLockIT::resolveAccountUrl);
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
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private LockProvider lockProvider;

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

    private PostEntity seedPendingPost(UUID mediaId, String blobUrl, Instant createdAt) {
        var postEntity = PostEntity.builder()
                .id(UUID.randomUUID())
                .userId(AUTHOR_ID)
                .postType(PostType.BASIC)
                .description("shedlock-it post")
                .tags(List.of("test"))
                .status(PostStatus.PENDING)
                .build();
        postEntity.addMedia(PostMediaEntity.builder()
                .id(mediaId)
                .url(blobUrl)
                .thumbnailUrl(blobUrl)
                .mediaType(MediaType.IMAGE)
                .mediaOrder(1)
                .build());
        var saved = postJpaRepository.save(postEntity);
        jdbcTemplate.update("UPDATE posts SET created_at = ? WHERE id = ?", Timestamp.from(createdAt), saved.getId());
        return saved;
    }

    private boolean blobExists(String blobUrl) {
        String blobName = blobUrl.substring(blobUrl.lastIndexOf('/') + 1);
        return blobContainerClient.getBlobClient(blobName).exists();
    }

    @Test
    void shouldPreventConcurrentExecutionAcrossTwoApplicationContexts() {
        var mediaId = UUID.randomUUID();
        var blobUrl = uploadBlob();
        var post = seedPendingPost(mediaId, blobUrl, Instant.now().minus(Duration.ofHours(49)));

        // Simulate a second application instance already running this run's job: acquire the
        // very same named lock directly through the shared JDBC-backed LockProvider, which is
        // exactly what @SchedulerLock's AOP interceptor does internally before invoking run().
        Optional<SimpleLock> otherInstanceLock = lockProvider.lock(
                new LockConfiguration(Instant.now(), LOCK_NAME, Duration.ofMinutes(55), Duration.ZERO));
        assertThat(otherInstanceLock).isPresent();

        // This instance's run() must be a complete no-op: the lock is already held.
        postMediaCleanupService.run();

        var untouched = postJpaRepository.findById(post.getId()).orElseThrow();
        assertThat(untouched.getStatus()).isEqualTo(PostStatus.PENDING);
        assertThat(untouched.getMediaPurgedAt()).isNull();
        assertThat(blobExists(blobUrl)).isTrue();

        otherInstanceLock.get().unlock();

        // Now that the lock is free, this instance can run normally.
        postMediaCleanupService.run();

        var processed = postJpaRepository.findById(post.getId()).orElseThrow();
        assertThat(processed.getStatus()).isEqualTo(PostStatus.MEDIA_UPLOAD_FAILED);
        assertThat(processed.getMediaPurgedAt()).isNotNull();
        assertThat(blobExists(blobUrl)).isFalse();
    }
}
