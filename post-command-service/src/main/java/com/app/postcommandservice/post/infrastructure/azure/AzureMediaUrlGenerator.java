package com.app.postcommandservice.post.infrastructure.azure;

import java.util.Objects;
import java.util.UUID;

import com.app.postcommandservice.post.application.port.GeneratedMediaUrls;
import com.app.postcommandservice.post.application.port.MediaUrlGenerator;
import com.app.postcommandservice.post.domain.model.valueobj.MediaType;
import com.app.postcommandservice.shared.infrastructure.azure.config.AzureStorageProperties;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Azure Blob Storage implementation of {@link MediaUrlGenerator}. Builds
 * {@code <accountUrl>/<container>/<uuid>} URLs purely from configuration — never from a
 * hardcoded host format — using a fresh random UUID per blob.
 */
@Component
@RequiredArgsConstructor
public class AzureMediaUrlGenerator implements MediaUrlGenerator {

    private final AzureStorageProperties properties;

    @Override
    public GeneratedMediaUrls generate(MediaType mediaType) {
        Objects.requireNonNull(mediaType, "mediaType must not be null");

        String contentUrl = blobUrl(UUID.randomUUID());

        if (mediaType == MediaType.IMAGE) {
            return new GeneratedMediaUrls(contentUrl, contentUrl);
        }

        String thumbnailUrl = blobUrl(UUID.randomUUID());
        return new GeneratedMediaUrls(contentUrl, thumbnailUrl);
    }

    private String blobUrl(UUID blobName) {
        String accountUrl = properties.getAccountUrl();
        String base = accountUrl.endsWith("/")
                ? accountUrl.substring(0, accountUrl.length() - 1)
                : accountUrl;
        return base + "/" + properties.getContainer() + "/" + blobName;
    }
}
