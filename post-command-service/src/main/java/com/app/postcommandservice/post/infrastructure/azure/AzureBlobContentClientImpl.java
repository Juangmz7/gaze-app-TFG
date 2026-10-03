package com.app.postcommandservice.post.infrastructure.azure;

import java.net.URI;
import java.nio.file.Path;
import java.util.Arrays;

import com.azure.core.util.Context;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.models.BlobDownloadContentResponse;
import com.azure.storage.blob.models.BlobRange;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
class AzureBlobContentClientImpl implements AzureBlobContentClient {

    private final BlobContainerClient blobContainerClient;

    @Override
    public long fetchContentLength(String blobUrl) {
        return blobClient(blobUrl).getProperties().getBlobSize();
    }

    @Override
    public byte[] downloadRange(String blobUrl, long offset, long count) {
        BlobDownloadContentResponse response = blobClient(blobUrl)
                .downloadContentWithResponse(null, null, new BlobRange(offset, count), false, null, Context.NONE);
        return response.getValue().toBytes();
    }

    @Override
    public void downloadToFile(String blobUrl, Path destination) {
        blobClient(blobUrl).downloadToFile(destination.toString());
    }

    private BlobClient blobClient(String blobUrl) {
        return blobContainerClient.getBlobClient(extractBlobName(blobUrl));
    }

    /**
     * The blob name is always the last path segment, regardless of whether the URL is
     * subdomain-style (cloud) or path-style (Azurite). Mirrors
     * {@code AzureMediaUploadUrlSigner#extractBlobName}.
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
