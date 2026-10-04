package com.app.postcommandservice.post.infrastructure.azure;

import java.util.Objects;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.app.postcommandservice.post.application.port.MediaBlobDeleter;

/**
 * Azure Blob Storage implementation of {@link MediaBlobDeleter} (task 39). Delegates to
 * {@link AzureBlobDeleteClient}, the testable seam over the Azure SDK's final client types.
 */
@Component
public class AzureMediaBlobDeleter implements MediaBlobDeleter {

    private final AzureBlobDeleteClient blobDeleteClient;

    AzureMediaBlobDeleter(AzureBlobDeleteClient blobDeleteClient) {
        this.blobDeleteClient = Objects.requireNonNull(blobDeleteClient, "blobDeleteClient must not be null");
    }

    @Override
    public void deleteIfExists(String blobUrl) {
        if (!StringUtils.hasText(blobUrl)) {
            throw new IllegalArgumentException("blobUrl must not be blank");
        }
        blobDeleteClient.deleteIfExists(blobUrl);
    }
}
