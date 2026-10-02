package com.app.postcommandservice.shared.infrastructure.azure.config;

import java.time.Duration;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Binds the Azure Blob Storage settings used to generate media URLs and
 * sign short-lived upload SAS tokens for post media (see {@code posts.media.upload-window}
 * in {@link com.app.postcommandservice.post.infrastructure.config.PostMediaProperties}
 * for the complementary business-rule window).
 *
 * <p>{@code accountKey} / {@code useAccountKey} / {@code allowHttp} only make sense for a
 * local Azurite emulator. In cloud environments {@code useAccountKey} must be {@code false}
 * so {@code DefaultAzureCredential} (user delegation SAS) is used instead, and
 * {@code allowHttp} must stay {@code false} so only HTTPS SAS URLs are produced.</p>
 */
@Data
@Validated
@ConfigurationProperties(prefix = "azure.storage")
public class AzureStorageProperties {

    /**
     * Blob service endpoint, e.g. {@code https://<account>.blob.core.windows.net}
     * (cloud) or {@code http://127.0.0.1:10000/<account>} (Azurite, path-style).
     * Never hardcode this value anywhere else in the codebase.
     */
    @NotBlank
    private String accountUrl;

    /**
     * Container name where post media blobs are stored.
     */
    @NotBlank
    private String container;

    /**
     * TTL for the upload SAS, counted from the moment it is generated.
     * The effective SAS expiry is {@code min(now + uploadSasTtl, post.createdAt + upload-window)}.
     */
    @NotNull
    private Duration uploadSasTtl = Duration.ofMinutes(15);

    /**
     * Only used for local/Azurite auth. Never set (and never read) in cloud environments.
     */
    private String accountKey;

    /**
     * When {@code true}, use account-key service SAS (local/Azurite only).
     * When {@code false} (default, cloud), use a cached user-delegation SAS via
     * {@code DefaultAzureCredential}.
     */
    private boolean useAccountKey = false;

    /**
     * When {@code true}, SAS URLs allow both HTTP and HTTPS (Azurite emulator only).
     * Cloud environments must keep this {@code false} (HTTPS only).
     */
    private boolean allowHttp = false;
}
