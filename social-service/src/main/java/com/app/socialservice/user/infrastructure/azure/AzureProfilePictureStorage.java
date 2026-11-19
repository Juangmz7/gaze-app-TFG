package com.app.socialservice.user.infrastructure.azure;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;

import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.models.BlobStorageException;
import com.azure.storage.blob.sas.BlobSasPermission;
import com.azure.storage.blob.sas.BlobServiceSasSignatureValues;
import com.azure.storage.common.sas.SasProtocol;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.app.socialservice.shared.infrastructure.azure.AzureMediaReadUrlSigner;
import com.app.socialservice.shared.infrastructure.azure.BlobContainerLocation;
import com.app.socialservice.shared.infrastructure.azure.config.AzureStorageProperties;
import com.app.socialservice.shared.infrastructure.azure.sas.BlobSasSigner;
import com.app.socialservice.user.application.dto.ProfilePictureUploadUrlResponse;
import com.app.socialservice.user.application.port.ProfilePictureStorage;
import com.app.socialservice.user.domain.model.valueobj.ProfilePictureBlobName;

/**
 * Avatars in the media container. The upload SAS grants Create + Write only ({@code sp=cw},
 * {@code sr=b}): never read, delete or list, and it lasts {@code azure.storage.upload-sas-ttl}
 * (15 min). In the cloud it is signed with the current read window's user delegation key,
 * which is valid for at least one more window, so it covers the 15 minutes.
 */
@Slf4j
@Component
public class AzureProfilePictureStorage implements ProfilePictureStorage {

    private static final int HTTP_NOT_FOUND = 404;

    private final BlobContainerLocation location;
    private final BlobSasSigner blobSasSigner;
    private final BlobContainerClient blobContainerClient;
    private final AzureMediaReadUrlSigner mediaReadUrlSigner;
    private final Duration uploadSasTtl;
    private final Clock clock;

    @Autowired
    public AzureProfilePictureStorage(
            BlobContainerLocation location,
            BlobSasSigner blobSasSigner,
            BlobContainerClient blobContainerClient,
            AzureMediaReadUrlSigner mediaReadUrlSigner,
            AzureStorageProperties properties) {
        this(location, blobSasSigner, blobContainerClient, mediaReadUrlSigner,
                requireCoveredByKey(Objects.requireNonNull(properties, "properties must not be null")),
                Clock.systemUTC());
    }

    AzureProfilePictureStorage(
            BlobContainerLocation location,
            BlobSasSigner blobSasSigner,
            BlobContainerClient blobContainerClient,
            AzureMediaReadUrlSigner mediaReadUrlSigner,
            Duration uploadSasTtl,
            Clock clock) {
        this.location = Objects.requireNonNull(location, "location must not be null");
        this.blobSasSigner = Objects.requireNonNull(blobSasSigner, "blobSasSigner must not be null");
        this.blobContainerClient = Objects.requireNonNull(blobContainerClient, "blobContainerClient must not be null");
        this.mediaReadUrlSigner = Objects.requireNonNull(mediaReadUrlSigner, "mediaReadUrlSigner must not be null");
        this.uploadSasTtl = requirePositive(uploadSasTtl);
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public ProfilePictureUploadUrlResponse createUploadUrl(ProfilePictureBlobName blobName) {
        Objects.requireNonNull(blobName, "blobName must not be null");
        var now = clock.instant();
        var expiresAt = now.plus(uploadSasTtl);

        var values = new BlobServiceSasSignatureValues(
                OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC),
                new BlobSasPermission().setCreatePermission(true).setWritePermission(true))
                .setProtocol(location.isPlainHttp() ? SasProtocol.HTTPS_HTTP : SasProtocol.HTTPS_ONLY);
        var sas = blobSasSigner.sign(blobName.value(), values, mediaReadUrlSigner.validityAt(now));

        var pictureUrl = location.blobUrl(blobName.value());
        return new ProfilePictureUploadUrlResponse(pictureUrl + "?" + sas, pictureUrl, expiresAt);
    }

    @Override
    public Optional<String> findContainerBlobName(String url) {
        return location.blobNameOfAnyUrl(url);
    }

    @Override
    public String blobUrl(ProfilePictureBlobName blobName) {
        Objects.requireNonNull(blobName, "blobName must not be null");
        return location.blobUrl(blobName.value());
    }

    @Override
    public boolean exists(ProfilePictureBlobName blobName) {
        Objects.requireNonNull(blobName, "blobName must not be null");
        try {
            blobContainerClient.getBlobClient(blobName.value()).getProperties();
            return true;
        } catch (BlobStorageException exception) {
            if (exception.getStatusCode() == HTTP_NOT_FOUND) {
                log.debug("Avatar blob not found: blob={}, errorCode={}", blobName.value(), exception.getErrorCode());
                return false;
            }
            throw exception;
        }
    }

    /**
     * The window's user delegation key outlives "now" by at least one window, so an upload SAS
     * longer than a window could outlive the key that signs it.
     */
    private static Duration requireCoveredByKey(AzureStorageProperties properties) {
        var ttl = properties.getUploadSasTtl();
        if (ttl != null && properties.getReadSasWindow() != null
                && ttl.compareTo(properties.getReadSasWindow()) > 0) {
            throw new IllegalStateException(
                    "azure.storage.upload-sas-ttl must not be longer than azure.storage.read-sas-window");
        }
        return ttl;
    }

    private static Duration requirePositive(Duration duration) {
        Objects.requireNonNull(duration, "uploadSasTtl must not be null");
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("uploadSasTtl must be positive");
        }
        return duration;
    }
}
