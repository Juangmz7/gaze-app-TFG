package com.app.socialservice.shared.infrastructure.azure.config;

import java.time.Duration;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The media storage account social-service reads profile pictures from and lets clients upload
 * avatars to. Same property names and the same container as post-command-service.
 *
 * <p>{@code accountKey} / {@code useAccountKey} are for the local Azurite emulator only (an
 * {@code http} account URL). In the cloud {@code useAccountKey} stays {@code false}: SAS are
 * signed with a user delegation key obtained through {@code DefaultAzureCredential}
 * (AKS Workload Identity).</p>
 */
@Data
@Validated
@ConfigurationProperties(prefix = "azure.storage")
public class AzureStorageProperties {

    /**
     * Blob service endpoint: {@code https://<account>.blob.core.windows.net} (cloud) or
     * {@code http://<host>:10000/<account>} (Azurite, path style).
     */
    @NotBlank
    private String accountUrl;

    /**
     * Media container (post-media-dev locally, post-media in prod). Only its blobs are signed.
     */
    @NotBlank
    private String container;

    /**
     * Lifetime of the write-only avatar upload SAS, counted from the moment it is generated.
     */
    @NotNull
    private Duration uploadSasTtl = Duration.ofMinutes(15);

    /**
     * Length of a read SAS signing window. A read SAS lasts two windows. Between 1 minute and
     * 72 hours, so a SAS never outlives the 7 days a user delegation key can last.
     */
    @NotNull
    private Duration readSasWindow = Duration.ofHours(24);

    /**
     * Azurite only. Never set in cloud environments.
     */
    private String accountKey;

    /**
     * {@code true}: sign with the account key (Azurite only, requires an http account URL).
     * {@code false} (default): sign with a cached user delegation key.
     */
    private boolean useAccountKey = false;
}
