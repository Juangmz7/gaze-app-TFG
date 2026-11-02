package com.app.postcommandservice.post.infrastructure.azure;

import java.net.URI;
import java.nio.file.Path;
import java.util.Arrays;

import com.azure.core.util.Context;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.models.BlobDownloadContentResponse;
import com.azure.storage.blob.models.BlobProperties;
import com.azure.storage.blob.models.BlobRange;
import com.azure.storage.blob.models.BlobRequestConditions;
import com.azure.storage.blob.options.BlobDownloadToFileOptions;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
class AzureBlobContentClientImpl implements AzureBlobContentClient {

    private final BlobContainerClient blobContainerClient;

    @Override
    public BlobSnapshot fetchProperties(String blobUrl) {
        BlobProperties properties = blobClient(blobUrl).getProperties();
        return new BlobSnapshot(properties.getBlobSize(), properties.getETag());
    }

    @Override
    public byte[] downloadRange(String blobUrl, String eTag, long offset, long count) {
        BlobDownloadContentResponse response = blobClient(blobUrl).downloadContentWithResponse(
                null, ifMatch(eTag), new BlobRange(offset, count), false, null, Context.NONE);
        return response.getValue().toBytes();
    }

    @Override
    public void downloadToFile(String blobUrl, String eTag, Path destination) {
        BlobDownloadToFileOptions options = new BlobDownloadToFileOptions(destination.toString())
                .setRequestConditions(ifMatch(eTag));
        blobClient(blobUrl).downloadToFileWithResponse(options, null, Context.NONE);
    }

    private static BlobRequestConditions ifMatch(String eTag) {
        return new BlobRequestConditions().setIfMatch(eTag);
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
