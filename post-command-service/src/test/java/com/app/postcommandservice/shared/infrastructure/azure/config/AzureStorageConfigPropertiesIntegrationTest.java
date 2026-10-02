package com.app.postcommandservice.shared.infrastructure.azure.config;

import java.time.Duration;

import com.app.postcommandservice.TestcontainersConfiguration;
import com.app.postcommandservice.post.infrastructure.config.PostMediaProperties;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the application context loads the Azure Storage / post-media properties from
 * {@code application.yaml} + {@code application-test.yaml} (which stands in for the
 * env-variable-driven values in {@code application-prod.yaml}), and that the
 * {@code BlobServiceClient}/{@code BlobContainerClient} beans are wired from them.
 */
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class AzureStorageConfigPropertiesIntegrationTest {

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Autowired
    private AzureStorageProperties azureStorageProperties;

    @Autowired
    private PostMediaProperties postMediaProperties;

    @Autowired
    private BlobServiceClient blobServiceClient;

    @Autowired
    private BlobContainerClient blobContainerClient;

    @Test
    void shouldLoadAzureStoragePropertiesFromConfiguration() {
        assertThat(azureStorageProperties.getAccountUrl()).isEqualTo("http://127.0.0.1:10000/devstoreaccount1");
        assertThat(azureStorageProperties.getContainer()).isEqualTo("post-media-test");
        assertThat(azureStorageProperties.getUploadSasTtl()).isEqualTo(Duration.ofMinutes(15));
        assertThat(azureStorageProperties.isUseAccountKey()).isTrue();
        assertThat(azureStorageProperties.isAllowHttp()).isTrue();
    }

    @Test
    void shouldLoadPostMediaUploadWindowFromConfiguration() {
        assertThat(postMediaProperties.getUploadWindow()).isEqualTo(Duration.ofHours(48));
    }

    @Test
    void shouldWireBlobServiceAndContainerClientBeansFromConfiguration() {
        assertThat(blobServiceClient).isNotNull();
        assertThat(blobServiceClient.getAccountUrl()).isEqualTo("http://127.0.0.1:10000/devstoreaccount1");
        assertThat(blobContainerClient).isNotNull();
        assertThat(blobContainerClient.getBlobContainerName()).isEqualTo("post-media-test");
    }
}
