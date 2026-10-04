package com.app.postcommandservice.post.infrastructure.azure;

import java.net.URI;
import java.util.Arrays;

import com.azure.storage.blob.BlobContainerClient;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
class AzureBlobDeleteClientImpl implements AzureBlobDeleteClient {

    private final BlobContainerClient blobContainerClient;

    @Override
    public void deleteIfExists(String blobUrl) {
        blobContainerClient.getBlobClient(extractBlobName(blobUrl)).deleteIfExists();
    }

    /**
     * The blob name is always the last path segment, regardless of whether the URL is
     * subdomain-style (cloud) or path-style (Azurite). Mirrors {@code
     * AzureBlobContentClientImpl#extractBlobName}.
     */
    private static String extractBlobName(String blobUrl) {
        String path = URI.create(blobUrl).getPath();
        String[] segments = Arrays.stream(path.split("/"))
                .filter(StringUtils::hasText)
                .toArray(String[]::new);

        if (segments.length == 0) {
            throw new IllegalArgumentException("blobUrl must contain a blob name: " + blobUrl);
        }
        return segments[segments.length - 1];
    }
}
