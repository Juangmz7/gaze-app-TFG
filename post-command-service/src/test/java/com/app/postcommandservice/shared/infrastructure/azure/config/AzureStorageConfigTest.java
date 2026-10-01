package com.app.postcommandservice.shared.infrastructure.azure.config;

import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AzureStorageConfigTest {

    private AzureStorageProperties properties;
    private AzureStorageConfig config;

    @BeforeEach
    void setUp() {
        properties = new AzureStorageProperties();
        properties.setContainer("post-media");
        config = new AzureStorageConfig(properties);
    }

    @Test
    void shouldBuildAccountKeyCredentialedClientFromPathStyleAzuriteUrl() {
        properties.setAccountUrl("http://127.0.0.1:10000/devstoreaccount1");
        properties.setUseAccountKey(true);
        properties.setAccountKey("Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==");

        BlobServiceClient client = config.blobServiceClient();

        assertThat(client).isNotNull();
        assertThat(client.getAccountUrl()).isEqualTo("http://127.0.0.1:10000/devstoreaccount1");

        BlobContainerClient containerClient = config.blobContainerClient(client);
        assertThat(containerClient.getBlobContainerName()).isEqualTo("post-media");
    }

    @Test
    void shouldFailWhenUseAccountKeyIsTrueButAccountKeyIsBlank() {
        properties.setAccountUrl("http://127.0.0.1:10000/devstoreaccount1");
        properties.setUseAccountKey(true);

        assertThatThrownBy(config::blobServiceClient).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldFailWhenAccountKeyAuthUrlHasNoPathSegmentToUseAsAccountName() {
        properties.setAccountUrl("http://127.0.0.1:10000/");
        properties.setUseAccountKey(true);
        properties.setAccountKey("some-key");

        assertThatThrownBy(config::blobServiceClient).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldBuildDefaultAzureCredentialedClientWhenNotUsingAccountKey() {
        properties.setAccountUrl("https://myaccount.blob.core.windows.net");
        properties.setUseAccountKey(false);

        BlobServiceClient client = config.blobServiceClient();

        assertThat(client).isNotNull();
        assertThat(client.getAccountUrl()).isEqualTo("https://myaccount.blob.core.windows.net");
    }
}
