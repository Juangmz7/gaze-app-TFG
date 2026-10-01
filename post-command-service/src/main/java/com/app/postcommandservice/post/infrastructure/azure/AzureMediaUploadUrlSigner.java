package com.app.postcommandservice.post.infrastructure.azure;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Objects;

import com.app.postcommandservice.post.application.port.MediaUploadUrlSigner;
import com.app.postcommandservice.post.application.port.SignedUploadUrl;
import com.app.postcommandservice.post.domain.exception.MediaUploadWindowExpiredException;
import com.app.postcommandservice.post.infrastructure.config.PostMediaProperties;
import com.app.postcommandservice.shared.infrastructure.azure.config.AzureStorageProperties;
import com.azure.storage.blob.models.UserDelegationKey;
import com.azure.storage.blob.sas.BlobSasPermission;
import com.azure.storage.blob.sas.BlobServiceSasSignatureValues;
import com.azure.storage.common.sas.SasProtocol;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Azure Blob Storage implementation of {@link MediaUploadUrlSigner}.
 *
 * <p><strong>Permission choice:</strong> grants Create + Write ({@code cw}), never Read,
 * Delete or List. A bare Create-only ({@code c}) permission was considered (per the task's
 * pitfall about long-lived SAS widening the overwrite window), but the client upload path
 * must support both the single-shot "Put Blob" call (used for images/small blobs, which
 * needs Create) and the chunked "Put Block" + "Put Block List" calls used for large video
 * uploads (which write to and then commit an uncommitted blob). Azure does not clearly
 * document Create-only as sufficient for the Put Block flow, so Create+Write is the safer
 * choice that still satisfies the "write-only" requirement: Read, Delete and List are never
 * granted, so a verified blob can never be read back or re-listed through this SAS, and the
 * short expiry (capped by the post's upload window) is what actually bounds the
 * overwrite-after-verification risk.</p>
 *
 * <p><strong>Auth:</strong> when {@code azure.storage.use-account-key=true} (local/Azurite),
 * signs with a service SAS using the account-key-backed container client. Otherwise (cloud,
 * default), signs with a user-delegation SAS, caching the delegation key (valid up to 7 days)
 * and refreshing it ahead of expiry rather than requesting one per signature.</p>
 *
 * <p>SAS URLs are never logged and never persisted: they only ever exist as the return value
 * of {@link #sign(String, Instant)}.</p>
 */
@Component
public class AzureMediaUploadUrlSigner implements MediaUploadUrlSigner {

    /**
     * User delegation keys may be valid for up to 7 days; stay comfortably under that.
     */
    private static final Duration DELEGATION_KEY_VALIDITY = Duration.ofDays(6);

    /**
     * Refresh the cached delegation key this long before it actually expires.
     */
    private static final Duration DELEGATION_KEY_REFRESH_MARGIN = Duration.ofHours(1);

    private final AzureBlobSasClient blobSasClient;
    private final AzureStorageProperties storageProperties;
    private final PostMediaProperties mediaProperties;

    private final Object delegationKeyLock = new Object();
    private volatile UserDelegationKey cachedDelegationKey;
    private volatile Instant cachedDelegationKeyExpiry;

    AzureMediaUploadUrlSigner(
            AzureBlobSasClient blobSasClient,
            AzureStorageProperties storageProperties,
            PostMediaProperties mediaProperties) {
        this.blobSasClient = Objects.requireNonNull(blobSasClient, "blobSasClient must not be null");
        this.storageProperties = Objects.requireNonNull(storageProperties, "storageProperties must not be null");
        this.mediaProperties = Objects.requireNonNull(mediaProperties, "mediaProperties must not be null");
    }

    @Override
    public SignedUploadUrl sign(String blobUrl, Instant postCreatedAt) {
        if (!StringUtils.hasText(blobUrl)) {
            throw new IllegalArgumentException("blobUrl must not be blank");
        }
        Objects.requireNonNull(postCreatedAt, "postCreatedAt must not be null");

        Instant now = Instant.now();
        Instant ttlExpiry = now.plus(storageProperties.getUploadSasTtl());
        Instant windowExpiry = postCreatedAt.plus(mediaProperties.getUploadWindow());
        Instant expiry = ttlExpiry.isBefore(windowExpiry) ? ttlExpiry : windowExpiry;

        if (!expiry.isAfter(now)) {
            throw new MediaUploadWindowExpiredException(postCreatedAt, windowExpiry);
        }

        BlobSasPermission permission = new BlobSasPermission()
                .setCreatePermission(true)
                .setWritePermission(true);
        // Deliberately never set: read, delete, list permissions.

        BlobServiceSasSignatureValues sasValues = new BlobServiceSasSignatureValues(
                OffsetDateTime.ofInstant(expiry, ZoneOffset.UTC), permission)
                .setProtocol(storageProperties.isAllowHttp() ? SasProtocol.HTTPS_HTTP : SasProtocol.HTTPS_ONLY);

        String blobName = extractBlobName(blobUrl);

        String sasToken = storageProperties.isUseAccountKey()
                ? blobSasClient.generateAccountKeySas(blobName, sasValues)
                : blobSasClient.generateUserDelegationSas(blobName, sasValues, currentUserDelegationKey(now));

        String separator = blobUrl.contains("?") ? "&" : "?";
        return new SignedUploadUrl(blobUrl + separator + sasToken, expiry);
    }

    private UserDelegationKey currentUserDelegationKey(Instant now) {
        UserDelegationKey key = cachedDelegationKey;
        Instant expiry = cachedDelegationKeyExpiry;
        if (isUsable(key, expiry, now)) {
            return key;
        }

        synchronized (delegationKeyLock) {
            key = cachedDelegationKey;
            expiry = cachedDelegationKeyExpiry;
            if (isUsable(key, expiry, now)) {
                return key;
            }

            Instant newExpiry = now.plus(DELEGATION_KEY_VALIDITY);
            UserDelegationKey newKey = blobSasClient.fetchUserDelegationKey(
                    OffsetDateTime.ofInstant(now, ZoneOffset.UTC),
                    OffsetDateTime.ofInstant(newExpiry, ZoneOffset.UTC));

            cachedDelegationKey = newKey;
            cachedDelegationKeyExpiry = newExpiry;
            return newKey;
        }
    }

    private boolean isUsable(UserDelegationKey key, Instant expiry, Instant now) {
        return key != null && expiry != null && now.isBefore(expiry.minus(DELEGATION_KEY_REFRESH_MARGIN));
    }

    /**
     * The blob name is always the last path segment, regardless of whether the URL is
     * subdomain-style (cloud) or path-style (Azurite).
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
