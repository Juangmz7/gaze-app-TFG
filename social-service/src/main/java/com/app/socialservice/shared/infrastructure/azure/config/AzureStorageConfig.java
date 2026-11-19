package com.app.socialservice.shared.infrastructure.azure.config;

import java.time.Clock;

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

import com.app.socialservice.shared.infrastructure.azure.AzureMediaReadUrlSigner;
import com.app.socialservice.shared.infrastructure.azure.BlobContainerLocation;
import com.app.socialservice.shared.infrastructure.azure.sas.AccountKeyBlobSasSigner;
import com.app.socialservice.shared.infrastructure.azure.sas.AzureUserDelegationKeyFetcher;
import com.app.socialservice.shared.infrastructure.azure.sas.BlobSasSigner;
import com.app.socialservice.shared.infrastructure.azure.sas.UserDelegationBlobSasSigner;

/**
 * Azure Blob Storage clients and SAS signers of the media container, mirroring
 * post-command-service: account key for Azurite ({@code use-account-key=true}, http account
 * URL only), {@code DefaultAzureCredential} and user delegation keys otherwise. Building the
 * beans makes no network call.
 */
@Configuration
@EnableConfigurationProperties(AzureStorageProperties.class)
@RequiredArgsConstructor
public class AzureStorageConfig {

    private final AzureStorageProperties properties;

    @Bean
    public BlobContainerLocation blobContainerLocation() {
        var location = BlobContainerLocation.parse(properties.getAccountUrl(), properties.getContainer());
        if (properties.isUseAccountKey() && !location.isPlainHttp()) {
            throw new IllegalStateException(
                    "azure.storage.use-account-key=true is only allowed for an http (Azurite) account URL");
        }
        return location;
    }

    @Bean
    public BlobServiceClient blobServiceClient(BlobContainerLocation blobContainerLocation) {
        var builder = new BlobServiceClientBuilder().endpoint(properties.getAccountUrl());

        if (properties.isUseAccountKey()) {
            if (!StringUtils.hasText(properties.getAccountKey())) {
                throw new IllegalStateException(
                        "azure.storage.account-key must be set when azure.storage.use-account-key=true");
            }
            builder.credential(new StorageSharedKeyCredential(
                    blobContainerLocation.accountName(), properties.getAccountKey()));
        } else {
            builder.credential(new DefaultAzureCredentialBuilder().build());
        }

        return builder.buildClient();
    }

    @Bean
    public BlobContainerClient blobContainerClient(BlobServiceClient blobServiceClient) {
        return blobServiceClient.getBlobContainerClient(properties.getContainer());
    }

    @Bean
    public BlobSasSigner blobSasSigner(BlobServiceClient blobServiceClient, BlobContainerClient blobContainerClient) {
        if (properties.isUseAccountKey()) {
            return new AccountKeyBlobSasSigner(blobContainerClient);
        }
        return new UserDelegationBlobSasSigner(
                blobContainerClient,
                new AzureUserDelegationKeyFetcher(blobServiceClient),
                Clock.systemUTC());
    }

    @Bean
    public AzureMediaReadUrlSigner mediaReadUrlSigner(
            BlobContainerLocation blobContainerLocation,
            BlobSasSigner blobSasSigner) {
        return new AzureMediaReadUrlSigner(
                blobContainerLocation,
                blobSasSigner,
                properties.getReadSasWindow(),
                Clock.systemUTC());
    }
}
