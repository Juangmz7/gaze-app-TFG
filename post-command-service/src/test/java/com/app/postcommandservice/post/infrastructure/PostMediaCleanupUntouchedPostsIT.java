package com.app.postcommandservice.post.infrastructure;

import java.io.ByteArrayInputStream;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.common.StorageSharedKeyCredential;

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
 * Confirms the task-39 scheduled media-cleanup job never touches {@code ACCEPTED} posts or
 * posts younger than {@code posts.media.upload-window}, against a real Azurite emulator and
 * this service's own real Postgres/RabbitMQ containers. See {@code
 * PostMediaCleanupExpiredPendingFlowIT} for why this lives in its own file/Spring context.
 */
@Testcontainers
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class PostMediaCleanupUntouchedPostsIT {

    private static final UUID AUTHOR_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final String ACCOUNT_NAME = "devstoreaccount1";
    // Azurite's well-known default emulator key (local emulator only, never a real secret).
    private static final String ACCOUNT_KEY =
            "Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==";
    private static final String CONTAINER_NAME = "post-media-cleanup-untouched-it";

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
        registry.add("azure.storage.account-url", PostMediaCleanupUntouchedPostsIT::resolveAccountUrl);
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
        // @PrePersist always stamps createdAt = now(); backdate it directly so the test can
        // control the post's age without waiting real hours.
        jdbcTemplate.update("UPDATE posts SET created_at = ? WHERE id = ?", Timestamp.from(createdAt), saved.getId());
        return saved;
    }

    private boolean blobExists(String blobUrl) {
        String blobName = blobUrl.substring(blobUrl.lastIndexOf('/') + 1);
        return blobContainerClient.getBlobClient(blobName).exists();
    }

    @Test
    void shouldLeaveAcceptedAndRecentPostsUntouched() {
        var acceptedMediaId = UUID.randomUUID();
        var acceptedBlobUrl = uploadBlob();
        var acceptedPost = seedPost(acceptedMediaId, acceptedBlobUrl, PostStatus.ACCEPTED, Instant.now().minus(Duration.ofHours(49)));

        var recentMediaId = UUID.randomUUID();
        var recentBlobUrl = uploadBlob();
        var recentPost = seedPost(recentMediaId, recentBlobUrl, PostStatus.PENDING, Instant.now().minus(Duration.ofHours(1)));

        postMediaCleanupService.run();

        var reloadedAccepted = postJpaRepository.findById(acceptedPost.getId()).orElseThrow();
        assertThat(reloadedAccepted.getStatus()).isEqualTo(PostStatus.ACCEPTED);
        assertThat(reloadedAccepted.getMediaPurgedAt()).isNull();
        assertThat(blobExists(acceptedBlobUrl)).isTrue();

        var reloadedRecent = postJpaRepository.findById(recentPost.getId()).orElseThrow();
        assertThat(reloadedRecent.getStatus()).isEqualTo(PostStatus.PENDING);
        assertThat(reloadedRecent.getMediaPurgedAt()).isNull();
        assertThat(blobExists(recentBlobUrl)).isTrue();
    }
}
