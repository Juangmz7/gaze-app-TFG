package com.app.postcommandservice.post.infrastructure;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.app.postcommandservice.TestcontainersConfiguration;
import com.app.postcommandservice.post.domain.model.valueobj.PostStatus;
import com.app.postcommandservice.post.domain.model.valueobj.PostType;
import com.app.postcommandservice.post.infrastructure.entity.PostEntity;
import com.app.postcommandservice.post.infrastructure.entity.PostMediaEntity;
import com.app.postcommandservice.post.infrastructure.repository.PostJpaRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end coverage of the task-38 refresh-upload-urls endpoint against a real Azurite
 * emulator: the SAS url returned by {@code POST /api/posts/{postId}/media/upload-urls} must
 * actually allow uploading to the original (persisted) blob, exactly like the SAS urls issued
 * on post creation (task 33).
 */
@Testcontainers
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class RefreshUploadUrlsFlowIT {

    private static final UUID AUTHOR_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final String ACCOUNT_NAME = "devstoreaccount1";
    // Azurite's well-known default emulator key (local emulator only, never a real secret).
    private static final String ACCOUNT_KEY =
            "Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==";
    private static final String CONTAINER_NAME = "post-media-refresh-upload-urls-it";

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
        registry.add("azure.storage.account-url", RefreshUploadUrlsFlowIT::resolveAccountUrl);
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
    private WebApplicationContext webApplicationContext;

    @Autowired
    private PostJpaRepository postJpaRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @AfterEach
    void tearDown() {
        postJpaRepository.deleteAll();
    }

    private String blobUrl(String blobName) {
        return accountUrl + "/" + CONTAINER_NAME + "/" + blobName;
    }

    @Test
    void shouldReturnSasUrlsThatAllowUploadingToTheOriginalBlobs() throws Exception {
        var mockMvc = mockMvc();
        var mediaId = UUID.randomUUID();
        var blobName = UUID.randomUUID().toString();
        var post = seedPost(PostStatus.PENDING, mediaId, blobUrl(blobName));

        var result = mockMvc.perform(post("/api/posts/{postId}/media/upload-urls", post.getId())
                        .with(jwtFor(AUTHOR_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"media\":[]}"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("postId").asText()).isEqualTo(post.getId().toString());
        String uploadUrl = body.get("media").get(0).get("uploadUrl").asText();
        assertThat(uploadUrl).startsWith(blobUrl(blobName));

        HttpClient http = HttpClient.newHttpClient();
        HttpRequest putRequest = HttpRequest.newBuilder(URI.create(uploadUrl))
                .header("x-ms-blob-type", "BlockBlob")
                .header("Content-Type", "text/plain")
                .PUT(HttpRequest.BodyPublishers.ofString("uploaded via refreshed SAS"))
                .build();
        HttpResponse<String> putResponse = http.send(putRequest, HttpResponse.BodyHandlers.ofString());
        assertThat(putResponse.statusCode()).isBetween(200, 299);
        assertThat(blobContainerClient.getBlobClient(blobName).downloadContent().toString())
                .isEqualTo("uploaded via refreshed SAS");
    }

    @Test
    void shouldReuseTheSameSasUrlWhenStillValidAndClientPresentsIt() throws Exception {
        var mockMvc = mockMvc();
        var mediaId = UUID.randomUUID();
        var blobName = UUID.randomUUID().toString();
        var post = seedPost(PostStatus.PENDING, mediaId, blobUrl(blobName));

        var firstResult = mockMvc.perform(post("/api/posts/{postId}/media/upload-urls", post.getId())
                        .with(jwtFor(AUTHOR_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"media\":[]}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode firstBody = objectMapper.readTree(firstResult.getResponse().getContentAsString());
        String firstUploadUrl = firstBody.get("media").get(0).get("uploadUrl").asText();

        var secondRequestBody = objectMapper.createObjectNode();
        var mediaArray = secondRequestBody.putArray("media");
        var mediaNode = mediaArray.addObject();
        mediaNode.put("id", mediaId.toString());
        mediaNode.put("uploadUrl", firstUploadUrl);

        var secondResult = mockMvc.perform(post("/api/posts/{postId}/media/upload-urls", post.getId())
                        .with(jwtFor(AUTHOR_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(secondRequestBody)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode secondBody = objectMapper.readTree(secondResult.getResponse().getContentAsString());
        String secondUploadUrl = secondBody.get("media").get(0).get("uploadUrl").asText();

        assertThat(secondUploadUrl).isEqualTo(firstUploadUrl);
    }

    @Test
    void shouldReturnNotFoundWhenPostDoesNotExist() throws Exception {
        var mockMvc = mockMvc();

        mockMvc.perform(post("/api/posts/{postId}/media/upload-urls", UUID.randomUUID())
                        .with(jwtFor(AUTHOR_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"media\":[]}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldReturnForbiddenWhenRequesterIsNotTheAuthor() throws Exception {
        var mockMvc = mockMvc();
        var mediaId = UUID.randomUUID();
        var post = seedPost(PostStatus.PENDING, mediaId, blobUrl(UUID.randomUUID().toString()));

        mockMvc.perform(post("/api/posts/{postId}/media/upload-urls", post.getId())
                        .with(jwtFor(UUID.randomUUID()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"media\":[]}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldReturnConflictWhenPostIsNotPending() throws Exception {
        var mockMvc = mockMvc();
        var mediaId = UUID.randomUUID();
        var post = seedPost(PostStatus.ACCEPTED, mediaId, blobUrl(UUID.randomUUID().toString()));

        mockMvc.perform(post("/api/posts/{postId}/media/upload-urls", post.getId())
                        .with(jwtFor(AUTHOR_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"media\":[]}"))
                .andExpect(status().isConflict());
    }

    private PostEntity seedPost(PostStatus status, UUID mediaId, String blobUrl) {
        var postEntity = PostEntity.builder()
                .id(UUID.randomUUID())
                .userId(AUTHOR_ID)
                .postType(PostType.BASIC)
                .description("pending post")
                .status(status)
                .build();
        postEntity.addMedia(PostMediaEntity.builder()
                .id(mediaId)
                .url(blobUrl)
                .thumbnailUrl(blobUrl)
                .mediaType(com.app.postcommandservice.post.domain.model.valueobj.MediaType.IMAGE)
                .mediaOrder(1)
                .build());
        return postJpaRepository.save(postEntity);
    }

    private MockMvc mockMvc() {
        return MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    private SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor jwtFor(UUID userId) {
        return jwt().jwt(jwt -> jwt.subject(userId.toString()));
    }
}
