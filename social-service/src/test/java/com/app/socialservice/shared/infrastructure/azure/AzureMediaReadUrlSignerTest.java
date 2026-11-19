package com.app.socialservice.shared.infrastructure.azure;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.common.StorageSharedKeyCredential;

import org.junit.jupiter.api.Test;

import com.app.socialservice.shared.infrastructure.azure.sas.AccountKeyBlobSasSigner;
import com.app.socialservice.shared.infrastructure.azure.sas.BlobSasSigner;
import com.app.socialservice.shared.infrastructure.azure.sas.SasSigningKeyUnavailableException;
import com.app.socialservice.shared.infrastructure.azure.sas.SasValidity;

import static com.app.socialservice.shared.testutil.AzureStorageTestConstants.ACCOUNT_KEY;
import static com.app.socialservice.shared.testutil.AzureStorageTestConstants.ACCOUNT_NAME;
import static com.app.socialservice.shared.testutil.AzureStorageTestConstants.AZURITE_ACCOUNT_URL;
import static com.app.socialservice.shared.testutil.AzureStorageTestConstants.BLOB_RESOURCE;
import static com.app.socialservice.shared.testutil.AzureStorageTestConstants.CLOUD_ACCOUNT_URL;
import static com.app.socialservice.shared.testutil.AzureStorageTestConstants.CONTAINER;
import static com.app.socialservice.shared.testutil.AzureStorageTestConstants.READ_PERMISSION;
import static com.app.socialservice.shared.testutil.AzureStorageTestConstants.decoded;
import static com.app.socialservice.shared.testutil.AzureStorageTestConstants.queryParameters;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AzureMediaReadUrlSignerTest {

    private static final Duration WINDOW = Duration.ofHours(24);
    private static final Instant NOW = Instant.parse("2026-11-19T15:42:10Z");
    private static final Instant WINDOW_START = Instant.parse("2026-11-19T00:00:00Z");
    private static final String BLOB = "avatars/0b6e7b52-1f0e-4a43-9a52-7d4b1b0d3c11/picture.jpg";

    @Test
    void shouldLeaveNullForeignAndAlreadySignedUrlsUnchanged() {
        var signer = accountKeySigner(CLOUD_ACCOUNT_URL, NOW);
        var blobUrl = CLOUD_ACCOUNT_URL + "/" + CONTAINER + "/" + BLOB;

        assertThat(signer.signReadUrl(null)).isNull();
        assertThat(signer.signReadUrl("https://cdn.example.com/picture.png"))
                .isEqualTo("https://cdn.example.com/picture.png");
        assertThat(signer.signReadUrl(CLOUD_ACCOUNT_URL + "/other-container/" + BLOB))
                .isEqualTo(CLOUD_ACCOUNT_URL + "/other-container/" + BLOB);
        assertThat(signer.signReadUrl(blobUrl + "?sv=2025")).isEqualTo(blobUrl + "?sv=2025");
    }

    @Test
    void shouldSignAReadOnlyBlobSasForTheFixedWindowOverHttpsOnly() {
        var signer = accountKeySigner(CLOUD_ACCOUNT_URL, NOW);
        var blobUrl = CLOUD_ACCOUNT_URL + "/" + CONTAINER + "/" + BLOB;

        var signed = signer.signReadUrl(blobUrl);

        assertThat(signed).startsWith(blobUrl + "?");
        var query = queryParameters(signed);
        assertThat(query.get("sp")).isEqualTo(READ_PERMISSION);
        assertThat(query.get("sr")).isEqualTo(BLOB_RESOURCE);
        assertThat(query.get("spr")).isEqualTo("https");
        assertThat(decoded(query.get("st"))).isEqualTo("2026-11-18T23:55:00Z");
        assertThat(decoded(query.get("se"))).isEqualTo("2026-11-21T00:00:00Z");
        assertThat(query.get("sig")).isNotBlank();
    }

    @Test
    void shouldAllowHttpTooOnlyForAPlainHttpAzuriteAccount() {
        var signer = accountKeySigner(AZURITE_ACCOUNT_URL, NOW);

        var signed = signer.signReadUrl(AZURITE_ACCOUNT_URL + "/" + CONTAINER + "/" + BLOB);

        assertThat(decoded(queryParameters(signed).get("spr"))).isEqualTo("https,http");
    }

    @Test
    void shouldReturnByteIdenticalUrlsWithinOneWindowAndNewOnesInTheNext() {
        var blobUrl = CLOUD_ACCOUNT_URL + "/" + CONTAINER + "/" + BLOB;

        var morning = accountKeySigner(CLOUD_ACCOUNT_URL, WINDOW_START.plusSeconds(60)).signReadUrl(blobUrl);
        var evening = accountKeySigner(CLOUD_ACCOUNT_URL, WINDOW_START.plus(WINDOW).minusSeconds(1))
                .signReadUrl(blobUrl);
        var nextDay = accountKeySigner(CLOUD_ACCOUNT_URL, WINDOW_START.plus(WINDOW)).signReadUrl(blobUrl);

        assertThat(evening).isEqualTo(morning);
        assertThat(nextDay).isNotEqualTo(morning);
    }

    @Test
    void shouldComputeTheValidityFromTheEpochAlignedWindow() {
        var signer = accountKeySigner(CLOUD_ACCOUNT_URL, NOW);

        var validity = signer.validityAt(NOW);

        assertThat(validity.start()).isEqualTo(WINDOW_START.minus(AzureMediaReadUrlSigner.CLOCK_SKEW));
        assertThat(validity.expiry()).isEqualTo(WINDOW_START.plus(WINDOW.multipliedBy(2)));
    }

    @Test
    void shouldFallBackOnThePreviousWindowsKeyWhenTheCurrentOneIsUnavailable() {
        var requestedValidities = new ArrayList<SasValidity>();
        BlobSasSigner failingCurrentKey = (blobName, values, keyValidity) -> {
            requestedValidities.add(keyValidity);
            if (requestedValidities.size() == 1) {
                throw new SasSigningKeyUnavailableException("down", new IllegalStateException("down"));
            }
            return "sig=previous&se=" + values.getExpiryTime().toInstant();
        };
        var signer = new AzureMediaReadUrlSigner(
                BlobContainerLocation.parse(CLOUD_ACCOUNT_URL, CONTAINER), failingCurrentKey, WINDOW, clockAt(NOW));

        var signed = signer.signReadUrl(CLOUD_ACCOUNT_URL + "/" + CONTAINER + "/" + BLOB);

        assertThat(requestedValidities).hasSize(2);
        assertThat(requestedValidities.get(1).start()).isEqualTo(requestedValidities.get(0).start().minus(WINDOW));
        assertThat(signed).endsWith("sig=previous&se=" + WINDOW_START.plus(WINDOW));
    }

    @Test
    void shouldRejectWindowsOutsideTheAllowedRange() {
        var location = BlobContainerLocation.parse(CLOUD_ACCOUNT_URL, CONTAINER);
        BlobSasSigner signer = (blobName, values, keyValidity) -> "";

        assertThatThrownBy(() -> new AzureMediaReadUrlSigner(location, signer, Duration.ofSeconds(30), clockAt(NOW)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AzureMediaReadUrlSigner(location, signer, Duration.ofHours(73), clockAt(NOW)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(List.of(Duration.ofMinutes(1), Duration.ofHours(72)))
                .allSatisfy(window -> new AzureMediaReadUrlSigner(location, signer, window, clockAt(NOW)));
    }

    static BlobContainerClient accountKeyContainerClient(String accountUrl) {
        return new BlobServiceClientBuilder()
                .endpoint(accountUrl)
                .credential(new StorageSharedKeyCredential(ACCOUNT_NAME, ACCOUNT_KEY))
                .buildClient()
                .getBlobContainerClient(CONTAINER);
    }

    static Clock clockAt(Instant instant) {
        return Clock.fixed(instant, ZoneOffset.UTC);
    }

    private static AzureMediaReadUrlSigner accountKeySigner(String accountUrl, Instant now) {
        return new AzureMediaReadUrlSigner(
                BlobContainerLocation.parse(accountUrl, CONTAINER),
                new AccountKeyBlobSasSigner(accountKeyContainerClient(accountUrl)),
                WINDOW,
                clockAt(now));
    }
}
