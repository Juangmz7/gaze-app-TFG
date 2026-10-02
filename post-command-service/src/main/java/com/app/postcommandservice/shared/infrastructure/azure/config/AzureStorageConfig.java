package com.app.postcommandservice.shared.infrastructure.azure.config;

import java.net.URI;
import java.util.Arrays;
import java.util.Objects;

import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.common.StorageSharedKeyCredential;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * Wires the Azure Blob Storage SDK clients used by the media URL generator and
 * upload SAS signer (post bounded context). Credential choice is driven entirely
 * by {@link AzureStorageProperties#isUseAccountKey()}:
 *
 * <ul>
 *   <li>{@code true} (local/Azurite): a {@link StorageSharedKeyCredential}, built from the
 *       account name parsed out of the configured path-style account URL plus the
 *       configured account key.</li>
 *   <li>{@code false} (cloud, default): {@code DefaultAzureCredential}, so the signer must
 *       use a user-delegation SAS instead of an account key.</li>
 * </ul>
 */
@Configuration
@EnableConfigurationProperties(AzureStorageProperties.class)
@RequiredArgsConstructor
public class AzureStorageConfig {

    private final AzureStorageProperties properties;

    @Bean
    public BlobServiceClient blobServiceClient() {
        BlobServiceClientBuilder builder = new BlobServiceClientBuilder()
                .endpoint(properties.getAccountUrl());

        if (properties.isUseAccountKey()) {
            if (!StringUtils.hasText(properties.getAccountKey())) {
                throw new IllegalStateException(
                        "azure.storage.account-key must be set when azure.storage.use-account-key=true");
            }
            String accountName = resolvePathStyleAccountName(properties.getAccountUrl());
            builder.credential(new StorageSharedKeyCredential(accountName, properties.getAccountKey()));
        } else {
            builder.credential(new DefaultAzureCredentialBuilder().build());
        }

        return builder.buildClient();
    }

    @Bean
    public BlobContainerClient blobContainerClient(BlobServiceClient blobServiceClient) {
        return blobServiceClient.getBlobContainerClient(properties.getContainer());
    }

    /**
     * Account-key auth is restricted to local/Azurite, which always uses path-style URLs
     * (e.g. {@code http://127.0.0.1:10000/<account>}), so the account name is the first
     * path segment. This does not apply to (and is never used for) cloud subdomain-style
     * URLs, which authenticate via {@code DefaultAzureCredential} instead.
     */
    private static String resolvePathStyleAccountName(String accountUrl) {
        Objects.requireNonNull(accountUrl, "accountUrl must not be null");
        String path = URI.create(accountUrl).getPath();
        String firstSegment = Arrays.stream(path.split("/"))
                .filter(StringUtils::hasText)
                .findFirst()
                .orElse(null);

        if (!StringUtils.hasText(firstSegment)) {
            throw new IllegalStateException(
                    "Could not resolve the Azurite account name from azure.storage.account-url="
                            + accountUrl + "; expected a path-style URL such as http://host:10000/<account>");
        }
        return firstSegment;
    }
}
