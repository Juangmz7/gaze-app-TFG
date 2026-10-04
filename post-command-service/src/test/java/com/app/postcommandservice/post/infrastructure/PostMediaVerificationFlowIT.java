package com.app.postcommandservice.post.infrastructure;

import java.io.ByteArrayInputStream;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.app.postcommandservice.TestcontainersConfiguration;
import com.app.postcommandservice.post.domain.model.valueobj.MediaType;
import com.app.postcommandservice.post.domain.model.valueobj.PostStatus;
import com.app.postcommandservice.post.domain.model.valueobj.PostType;
import com.app.postcommandservice.post.infrastructure.entity.PostEntity;
import com.app.postcommandservice.post.infrastructure.entity.PostMediaEntity;
import com.app.postcommandservice.post.infrastructure.events.PostMediaUploadedEvent;
import com.app.postcommandservice.post.infrastructure.events.PostMediaUploadedMediaPayload;
import com.app.postcommandservice.post.infrastructure.repository.PostJpaRepository;
import com.app.postcommandservice.shared.infrastructure.rabbitmq.config.RabbitMQProperties;
import com.app.postcommandservice.shared.infrastructure.repository.OutboxEventRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end coverage of the task-37 media-upload verification flow against a real Azurite
 * emulator and this service's own real RabbitMQ/Postgres containers: a {@code
 * PostMediaUploadedEvent} published to {@code x.post.events}/{@code rk.post.media.uploaded} is
 * consumed by {@code PostMediaUploadedListener}, verified by the real {@code AzureMediaVerifier}
 * (no mocking of the verifier itself), and resolved to {@code ACCEPTED}/{@code
 * MEDIA_UPLOAD_FAILED} with the matching outbound event.
 */
@Testcontainers
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class PostMediaVerificationFlowIT {

    private static final UUID AUTHOR_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final String ACCOUNT_NAME = "devstoreaccount1";
    // Azurite's well-known default emulator key (local emulator only, never a real secret).
    private static final String ACCOUNT_KEY =
            "Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==";
    private static final String CONTAINER_NAME = "post-media-verification-flow-it";
    // Minimal valid JPEG magic-byte header (FF D8 FF), padded so the blob is non-empty.
    private static final byte[] VALID_JPEG = new byte[]{
            (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0, 'J', 'F', 'I', 'F', 0, 1, 0, 0, 0, 0, 0, 0
    };

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
        registry.add("azure.storage.account-url", PostMediaVerificationFlowIT::resolveAccountUrl);
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
    private PostJpaRepository postJpaRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private ConnectionFactory connectionFactory;

    @Autowired
    private RabbitMQProperties rabbitMQProperties;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @AfterEach
    void tearDown() {
        postJpaRepository.deleteAll();
        outboxEventRepository.deleteAll();
    }

    private String uploadBlob(byte[] content) {
        String blobName = UUID.randomUUID().toString();
        blobContainerClient.getBlobClient(blobName).upload(new ByteArrayInputStream(content), content.length, true);
        return accountUrl + "/" + CONTAINER_NAME + "/" + blobName;
    }

    @Test
    void shouldAcceptPostAndPublishSucceededAndCreatedEventsForValidUpload() throws Exception {
        var mediaId = UUID.randomUUID();
        var blobUrl = uploadBlob(VALID_JPEG);
        var post = seedPendingPost(mediaId, blobUrl, MediaType.IMAGE);

        var succeededQueue = bindTestQueue(rabbitMQProperties.getRk().getPost().getMedia().getValidation().getSucceeded());
        var createdQueue = bindTestQueue(rabbitMQProperties.getRk().getPost().getCreated());

        publishUploadedEvent(post.getId(), mediaId, blobUrl, MediaType.IMAGE);

        waitUntil(() -> postJpaRepository.findById(post.getId())
                .map(entity -> entity.getStatus() == PostStatus.ACCEPTED)
                .orElse(false));

        assertThat(postJpaRepository.findById(post.getId()).orElseThrow().getStatus())
                .isEqualTo(PostStatus.ACCEPTED);

        var succeededMessage = receiveMessage(succeededQueue);
        assertThat(succeededMessage).isNotNull();
        var succeededPayload = objectMapper.readValue(succeededMessage.getBody(), new TypeReference<java.util.Map<String, Object>>() { });
        assertThat(succeededPayload.get("postId")).isEqualTo(post.getId().toString());

        var createdMessage = receiveMessage(createdQueue);
        assertThat(createdMessage).isNotNull();
        var createdPayload = objectMapper.readValue(createdMessage.getBody(), new TypeReference<java.util.Map<String, Object>>() { });
        assertThat(createdPayload.get("postId")).isEqualTo(post.getId().toString());

        deleteQueue(succeededQueue);
        deleteQueue(createdQueue);
    }

    @Test
    void shouldIgnoreEventAndPublishNothingWhenPayloadMediaIdsDoNotMatchPersistedPost() throws Exception {
        var persistedMediaId = UUID.randomUUID();
        var blobUrl = uploadBlob(VALID_JPEG);
        var post = seedPendingPost(persistedMediaId, blobUrl, MediaType.IMAGE);

        var failedQueue = bindTestQueue(rabbitMQProperties.getRk().getPost().getMedia().getValidation().getFailed());

        // Payload references a different media id than the one persisted for this post.
        publishUploadedEvent(post.getId(), UUID.randomUUID(), blobUrl, MediaType.IMAGE);

        // Mismatched media ids are silently ignored (acceptance: "duplicated message for a
        // non-matching post, nothing changes and nothing is published"), so the post stays
        // PENDING and nothing lands on the failed queue.
        Thread.sleep(1500);
        assertThat(postJpaRepository.findById(post.getId()).orElseThrow().getStatus())
                .isEqualTo(PostStatus.PENDING);
        assertThat(receiveMessage(failedQueue)).isNull();

        deleteQueue(failedQueue);
    }

    @Test
    void shouldFailPostAndPublishFailedEventWithCustomReasonWhenBlobIsMissing() throws Exception {
        var mediaId = UUID.randomUUID();
        var missingBlobUrl = accountUrl + "/" + CONTAINER_NAME + "/" + UUID.randomUUID();
        var post = seedPendingPost(mediaId, missingBlobUrl, MediaType.IMAGE);

        var failedQueue = bindTestQueue(rabbitMQProperties.getRk().getPost().getMedia().getValidation().getFailed());

        publishUploadedEvent(post.getId(), mediaId, missingBlobUrl, MediaType.IMAGE);

        waitUntil(() -> postJpaRepository.findById(post.getId())
                .map(entity -> entity.getStatus() == PostStatus.MEDIA_UPLOAD_FAILED)
                .orElse(false));

        var failedMessage = receiveMessage(failedQueue);
        assertThat(failedMessage).isNotNull();
        var failedPayload = objectMapper.readValue(failedMessage.getBody(), new TypeReference<java.util.Map<String, Object>>() { });
        assertThat(failedPayload.get("postId")).isEqualTo(post.getId().toString());
        assertThat(failedPayload.get("reasonCode")).isEqualTo("BLOB_NOT_FOUND");
        assertThat((String) failedPayload.get("reason")).contains(post.getId().toString());

        deleteQueue(failedQueue);
    }

    private PostEntity seedPendingPost(UUID mediaId, String blobUrl, MediaType mediaType) {
        var postEntity = PostEntity.builder()
                .id(UUID.randomUUID())
                .userId(AUTHOR_ID)
                .postType(PostType.BASIC)
                .description("pending post")
                .tags(List.of("test"))
                .status(PostStatus.PENDING)
                .build();
        postEntity.addMedia(PostMediaEntity.builder()
                .id(mediaId)
                .url(blobUrl)
                .thumbnailUrl(blobUrl)
                .mediaType(mediaType)
                .mediaOrder(1)
                .build());
        return postJpaRepository.save(postEntity);
    }

    private void publishUploadedEvent(UUID postId, UUID mediaId, String blobUrl, MediaType mediaType) {
        var event = PostMediaUploadedEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(UUID.randomUUID())
                .occurredAt(Instant.now())
                .postId(postId)
                .media(List.of(PostMediaUploadedMediaPayload.builder()
                        .id(mediaId)
                        .url(blobUrl)
                        .thumbnailUrl(blobUrl)
                        .mediaType(mediaType)
                        .order(1)
                        .build()))
                .build();

        rabbitTemplate.convertAndSend(
                rabbitMQProperties.getExchange().getPost().getEvents(),
                rabbitMQProperties.getRk().getPost().getMedia().getUploaded(),
                event
        );
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

    private void waitUntil(Check condition) throws InterruptedException {
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
}
