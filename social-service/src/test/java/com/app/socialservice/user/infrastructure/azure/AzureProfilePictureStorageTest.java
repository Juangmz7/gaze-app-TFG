package com.app.socialservice.user.infrastructure.azure;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.common.StorageSharedKeyCredential;

import org.junit.jupiter.api.Test;

import com.app.socialservice.shared.infrastructure.azure.AzureMediaReadUrlSigner;
import com.app.socialservice.shared.infrastructure.azure.BlobContainerLocation;
import com.app.socialservice.shared.infrastructure.azure.config.AzureStorageProperties;
import com.app.socialservice.shared.infrastructure.azure.sas.AccountKeyBlobSasSigner;
import com.app.socialservice.user.domain.enums.ProfilePictureContentType;
import com.app.socialservice.user.domain.model.valueobj.ProfilePictureBlobName;
import com.app.socialservice.user.domain.model.valueobj.UserId;

import static com.app.socialservice.shared.testutil.AzureStorageTestConstants.ACCOUNT_KEY;
import static com.app.socialservice.shared.testutil.AzureStorageTestConstants.ACCOUNT_NAME;
import static com.app.socialservice.shared.testutil.AzureStorageTestConstants.BLOB_RESOURCE;
import static com.app.socialservice.shared.testutil.AzureStorageTestConstants.CLOUD_ACCOUNT_URL;
import static com.app.socialservice.shared.testutil.AzureStorageTestConstants.CONTAINER;
import static com.app.socialservice.shared.testutil.AzureStorageTestConstants.UPLOAD_PERMISSION;
import static com.app.socialservice.shared.testutil.AzureStorageTestConstants.decoded;
import static com.app.socialservice.shared.testutil.AzureStorageTestConstants.queryParameters;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AzureProfilePictureStorageTest {

    private static final Instant NOW = Instant.parse("2026-11-19T15:42:10Z");
    private static final Duration UPLOAD_SAS_TTL = Duration.ofMinutes(15);
    private static final UUID USER_ID = UUID.fromString("0b6e7b52-1f0e-4a43-9a52-7d4b1b0d3c11");

    private final BlobContainerLocation location = BlobContainerLocation.parse(CLOUD_ACCOUNT_URL, CONTAINER);
    private final BlobContainerClient containerClient = new BlobServiceClientBuilder()
            .endpoint(CLOUD_ACCOUNT_URL)
            .credential(new StorageSharedKeyCredential(ACCOUNT_NAME, ACCOUNT_KEY))
            .buildClient()
            .getBlobContainerClient(CONTAINER);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final AzureProfilePictureStorage storage = new AzureProfilePictureStorage(
            location,
            new AccountKeyBlobSasSigner(containerClient),
            containerClient,
            new AzureMediaReadUrlSigner(location, new AccountKeyBlobSasSigner(containerClient),
                    Duration.ofHours(24), clock),
            UPLOAD_SAS_TTL,
            clock);

    @Test
    void shouldSignAWriteOnlyBlobSasThatExpiresInFifteenMinutesOnTheAvatarBlob() {
        var blobName = ProfilePictureBlobName.generate(new UserId(USER_ID), ProfilePictureContentType.PNG);

        var response = storage.createUploadUrl(blobName);

        assertThat(blobName.value()).matches("avatars/" + USER_ID + "/[0-9a-f-]{36}\\.png");
        assertThat(response.pictureUrl()).isEqualTo(CLOUD_ACCOUNT_URL + "/" + CONTAINER + "/" + blobName.value());
        assertThat(response.pictureUrl()).doesNotContain("?");
        assertThat(response.uploadUrl()).startsWith(response.pictureUrl() + "?");
        assertThat(response.expiresAt()).isEqualTo(NOW.plus(UPLOAD_SAS_TTL));

        var query = queryParameters(response.uploadUrl());
        assertThat(query.get("sp")).isEqualTo(UPLOAD_PERMISSION);
        assertThat(query.get("sr")).isEqualTo(BLOB_RESOURCE);
        assertThat(query.get("spr")).isEqualTo("https");
        assertThat(decoded(query.get("se"))).isEqualTo("2026-11-19T15:57:10Z");
    }

    @Test
    void shouldRecogniseUrlsOfTheContainerAndBuildBareBlobUrls() {
        var blobName = ProfilePictureBlobName.generate(new UserId(USER_ID), ProfilePictureContentType.JPEG);
        var bareUrl = storage.blobUrl(blobName);

        assertThat(storage.findContainerBlobName(bareUrl + "?sv=2025&sig=x")).hasValue(blobName.value());
        assertThat(storage.findContainerBlobName("https://cdn.example.com/picture.png")).isEmpty();
    }

    @Test
    void shouldRejectAnUploadSasLongerThanTheReadWindow() {
        var properties = new AzureStorageProperties();
        properties.setUploadSasTtl(Duration.ofHours(2));
        properties.setReadSasWindow(Duration.ofHours(1));
        var readSigner = new AzureMediaReadUrlSigner(
                location, new AccountKeyBlobSasSigner(containerClient), Duration.ofHours(1), clock);

        assertThatThrownBy(() -> new AzureProfilePictureStorage(
                location, new AccountKeyBlobSasSigner(containerClient), containerClient, readSigner, properties))
                .isInstanceOf(IllegalStateException.class);
    }
}
