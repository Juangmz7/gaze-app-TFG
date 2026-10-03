package com.app.postcommandservice.post.infrastructure.azure;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.app.postcommandservice.post.application.port.MediaToVerify;
import com.app.postcommandservice.post.application.port.MediaVerificationFailureReason;
import com.app.postcommandservice.post.application.port.MediaVerificationResult;
import com.app.postcommandservice.post.domain.model.valueobj.MediaType;
import com.app.postcommandservice.post.infrastructure.config.PostMediaProperties;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.common.StorageSharedKeyCredential;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves {@link AzureMediaVerifier} end-to-end against a real Azurite emulator, mirroring
 * {@link AzureMediaUploadUrlSignerAzuriteTest}'s container setup/pattern.
 */
@Testcontainers
class AzureMediaVerifierAzuriteTest {

    private static final String ACCOUNT_NAME = "devstoreaccount1";
    // Azurite's well-known default emulator key (local emulator only, never a real secret).
    private static final String ACCOUNT_KEY =
            "Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==";
    private static final String CONTAINER_NAME = "post-media-verifier-azurite-test";

    @Container
    private static final GenericContainer<?> AZURITE = new GenericContainer<>(
            DockerImageName.parse("mcr.microsoft.com/azure-storage/azurite:latest"))
            .withExposedPorts(10000)
            .waitingFor(Wait.forLogMessage(".*Azurite Blob service is successfully listening.*\\n", 1));

    private static BlobServiceClient blobServiceClient;
    private static BlobContainerClient blobContainerClient;
    private static String accountUrl;

    @BeforeAll
    static void setUpContainer() {
        accountUrl = "http://" + AZURITE.getHost() + ":" + AZURITE.getMappedPort(10000) + "/" + ACCOUNT_NAME;

        blobServiceClient = new BlobServiceClientBuilder()
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

    private AzureMediaVerifier newVerifier() {
        AzureBlobContentClient contentClient = new AzureBlobContentClientImpl(blobContainerClient);
        return new AzureMediaVerifier(contentClient, new PostMediaProperties());
    }

    private String uploadBlob(byte[] content) {
        String blobName = UUID.randomUUID().toString();
        blobContainerClient.getBlobClient(blobName).upload(
                new ByteArrayInputStream(content), content.length, true);
        return accountUrl + "/" + CONTAINER_NAME + "/" + blobName;
    }

    @Test
    void shouldPassForAValidImageAndAValidVideo() {
        UUID imageId = UUID.randomUUID();
        UUID videoId = UUID.randomUUID();

        String imageUrl = uploadBlob(MediaVerifierFixtures.jpeg(2048));
        String videoUrl = uploadBlob(MediaVerifierFixtures.validMp4(7, 600));
        String thumbnailUrl = uploadBlob(MediaVerifierFixtures.png(1024));

        MediaVerificationResult result = newVerifier().verify(List.of(
                new MediaToVerify(imageId, imageUrl, imageUrl, MediaType.IMAGE),
                new MediaToVerify(videoId, videoUrl, thumbnailUrl, MediaType.VIDEO)));

        assertThat(result).isInstanceOf(MediaVerificationResult.Success.class);
        Map<UUID, Integer> durations = ((MediaVerificationResult.Success) result).videoDurationsSeconds();
        assertThat(durations).containsEntry(videoId, 7);
        assertThat(durations).doesNotContainKey(imageId);
    }

    @Test
    void shouldFailWhenTheBlobDoesNotExist() {
        UUID imageId = UUID.randomUUID();
        String missingBlobUrl = accountUrl + "/" + CONTAINER_NAME + "/" + UUID.randomUUID();

        MediaVerificationResult result = newVerifier().verify(List.of(
                new MediaToVerify(imageId, missingBlobUrl, missingBlobUrl, MediaType.IMAGE)));

        assertThat(result).isInstanceOf(MediaVerificationResult.Failure.class);
        assertThat(((MediaVerificationResult.Failure) result).reason())
                .isEqualTo(MediaVerificationFailureReason.BLOB_NOT_FOUND);
    }

    @Test
    void shouldFailWhenTheVideoThumbnailIsActuallyAVideo() {
        UUID videoId = UUID.randomUUID();

        String videoUrl = uploadBlob(MediaVerifierFixtures.validMp4(3, 600));
        String thumbnailUrl = uploadBlob(MediaVerifierFixtures.validMp4(3, 600));

        MediaVerificationResult result = newVerifier().verify(List.of(
                new MediaToVerify(videoId, videoUrl, thumbnailUrl, MediaType.VIDEO)));

        assertThat(result).isInstanceOf(MediaVerificationResult.Failure.class);
        assertThat(((MediaVerificationResult.Failure) result).reason())
                .isEqualTo(MediaVerificationFailureReason.TYPE_MISMATCH);
    }

    @Test
    void shouldFailWhenTheBlobExceedsTheConfiguredSizeLimit() {
        UUID imageId = UUID.randomUUID();
        AzureBlobContentClient contentClient = new AzureBlobContentClientImpl(blobContainerClient);
        PostMediaProperties properties = new PostMediaProperties();
        properties.setMaxImageBytes(100);
        AzureMediaVerifier verifier = new AzureMediaVerifier(contentClient, properties);

        String imageUrl = uploadBlob(MediaVerifierFixtures.jpeg(2048));

        MediaVerificationResult result = verifier.verify(List.of(
                new MediaToVerify(imageId, imageUrl, imageUrl, MediaType.IMAGE)));

        assertThat(result).isInstanceOf(MediaVerificationResult.Failure.class);
        assertThat(((MediaVerificationResult.Failure) result).reason())
                .isEqualTo(MediaVerificationFailureReason.FILE_TOO_LARGE);
    }

    @Test
    void shouldFailForACorruptFile() {
        UUID imageId = UUID.randomUUID();
        String corruptUrl = uploadBlob(MediaVerifierFixtures.garbage(256));

        MediaVerificationResult result = newVerifier().verify(List.of(
                new MediaToVerify(imageId, corruptUrl, corruptUrl, MediaType.IMAGE)));

        assertThat(result).isInstanceOf(MediaVerificationResult.Failure.class);
        assertThat(((MediaVerificationResult.Failure) result).reason())
                .isEqualTo(MediaVerificationFailureReason.CORRUPT_FILE);
    }

    @Test
    void shouldExtractDurationFromAnMp4WhoseMoovAtomIsAtTheEndOfTheFile() {
        UUID videoId = UUID.randomUUID();

        byte[] mp4WithTrailingMoov = MediaVerifierFixtures.validMp4WithMoovAtEnd(42, 600, 50_000);
        String videoUrl = uploadBlob(mp4WithTrailingMoov);
        String thumbnailUrl = uploadBlob(MediaVerifierFixtures.jpeg(1024));

        MediaVerificationResult result = newVerifier().verify(List.of(
                new MediaToVerify(videoId, videoUrl, thumbnailUrl, MediaType.VIDEO)));

        assertThat(result).isInstanceOf(MediaVerificationResult.Success.class);
        assertThat(((MediaVerificationResult.Success) result).videoDurationsSeconds())
                .containsEntry(videoId, 42);
    }
}
