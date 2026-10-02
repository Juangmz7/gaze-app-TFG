package com.app.postcommandservice.post.infrastructure.azure;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.app.postcommandservice.post.application.port.SignedUploadUrl;
import com.app.postcommandservice.post.infrastructure.config.PostMediaProperties;
import com.app.postcommandservice.shared.infrastructure.azure.config.AzureStorageProperties;
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
 * Proves end-to-end, against a real Azurite emulator, that the SAS produced by
 * {@link AzureMediaUploadUrlSigner} allows uploading the blob (write-only permission
 * works for the client upload) but rejects reading it back (no read permission granted).
 *
 * <p>No {@code testcontainers-azurite} module exists on Maven Central (checked via
 * search.maven.org), so this uses a generic container on the official Azurite image,
 * mirroring how this project wires Postgres/RabbitMQ via plain Testcontainers.</p>
 */
@Testcontainers
class AzureMediaUploadUrlSignerAzuriteTest {

    private static final String ACCOUNT_NAME = "devstoreaccount1";
    // Azurite's well-known default emulator key (local emulator only, never a real secret).
    private static final String ACCOUNT_KEY =
            "Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==";
    private static final String CONTAINER_NAME = "post-media-azurite-test";

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

    @Test
    void sasUrlShouldAllowUploadingTheBlobAndRejectReadingIt() throws Exception {
        AzureStorageProperties storageProperties = new AzureStorageProperties();
        storageProperties.setAccountUrl(accountUrl);
        storageProperties.setContainer(CONTAINER_NAME);
        storageProperties.setUploadSasTtl(Duration.ofMinutes(15));
        storageProperties.setUseAccountKey(true);
        storageProperties.setAccountKey(ACCOUNT_KEY);
        storageProperties.setAllowHttp(true);

        PostMediaProperties mediaProperties = new PostMediaProperties();
        mediaProperties.setUploadWindow(Duration.ofHours(48));

        AzureBlobSasClient blobSasClient = new AzureBlobSasClientImpl(blobServiceClient, blobContainerClient);
        AzureMediaUploadUrlSigner signer = new AzureMediaUploadUrlSigner(
                blobSasClient, storageProperties, mediaProperties);

        String blobName = UUID.randomUUID().toString();
        String plainBlobUrl = accountUrl + "/" + CONTAINER_NAME + "/" + blobName;

        SignedUploadUrl signed = signer.sign(plainBlobUrl, Instant.now().minus(Duration.ofHours(1)));

        HttpClient http = HttpClient.newHttpClient();

        HttpRequest putRequest = HttpRequest.newBuilder(URI.create(signed.url()))
                .header("x-ms-blob-type", "BlockBlob")
                .header("Content-Type", "text/plain")
                .PUT(HttpRequest.BodyPublishers.ofString("hello from the client upload"))
                .build();
        HttpResponse<String> putResponse = http.send(putRequest, HttpResponse.BodyHandlers.ofString());
        assertThat(blobContainerClient.getBlobClient(blobName).downloadContent().toString())
                .isEqualTo("hello from the client upload");

        HttpRequest getRequest = HttpRequest.newBuilder(URI.create(signed.url())).GET().build();
        HttpResponse<String> getResponse = http.send(getRequest, HttpResponse.BodyHandlers.ofString());
        assertThat(getResponse.headers().firstValue("x-ms-error-code"))
                .hasValue("AuthorizationPermissionMismatch");
    }
}
