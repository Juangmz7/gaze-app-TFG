package com.app.postcommandservice.post.infrastructure.azure;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.app.postcommandservice.post.application.port.MediaBlobDeleter;

/**
 * Azure adapter of {@link MediaBlobDeleter}: a {@code deleteIfExists} on the blob of the
 * configured container whose name is the url's last path segment (same resolution as the
 * verifier and the SAS signer).
 */
@Component
@RequiredArgsConstructor
public class AzureMediaBlobDeleter implements MediaBlobDeleter {

    private final AzureBlobContentClient azureBlobContentClient;

    @Override
    public boolean deleteIfExists(String blobUrl) {
        if (!StringUtils.hasText(blobUrl)) {
            throw new IllegalArgumentException("blobUrl must not be blank");
        }
        return azureBlobContentClient.deleteIfExists(blobUrl);
    }
}
