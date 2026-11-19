package com.app.socialservice.shared.infrastructure.azure.sas;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.models.UserDelegationKey;
import com.azure.storage.blob.sas.BlobServiceSasSignatureValues;

import lombok.extern.slf4j.Slf4j;

/**
 * User delegation SAS (cloud). The key of a signing window is fetched once and is valid
 * exactly as long as the read SAS of that window, so every replica asks for the same validity
 * and signs byte-identical URLs. The previous window's key is kept, so the read signer can
 * fall back on it while a fetch fails. A failed fetch is remembered for
 * {@link #FETCH_RETRY_INTERVAL}, so an outage does not turn every request into a call.
 */
@Slf4j
public class UserDelegationBlobSasSigner implements BlobSasSigner {

    static final Duration FETCH_RETRY_INTERVAL = Duration.ofSeconds(30);

    private final BlobContainerClient blobContainerClient;
    private final UserDelegationKeyFetcher keyFetcher;
    private final Clock clock;

    private final Object lock = new Object();
    private CachedKey current;
    private CachedKey previous;
    private RuntimeException lastFailure;
    private Instant retryAt;

    public UserDelegationBlobSasSigner(
            BlobContainerClient blobContainerClient,
            UserDelegationKeyFetcher keyFetcher,
            Clock clock) {
        this.blobContainerClient = Objects.requireNonNull(blobContainerClient, "blobContainerClient must not be null");
        this.keyFetcher = Objects.requireNonNull(keyFetcher, "keyFetcher must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public String sign(String blobName, BlobServiceSasSignatureValues values, SasValidity keyValidity) {
        AccountKeyBlobSasSigner.requireBlobName(blobName);
        Objects.requireNonNull(values, "values must not be null");
        Objects.requireNonNull(keyValidity, "keyValidity must not be null");

        var key = keyFor(keyValidity);
        return blobContainerClient.getBlobClient(blobName).generateUserDelegationSas(values, key);
    }

    private UserDelegationKey keyFor(SasValidity validity) {
        synchronized (lock) {
            for (var cached : new CachedKey[] {current, previous}) {
                if (cached != null && cached.validity().equals(validity)) {
                    return cached.key();
                }
            }
            var now = clock.instant();
            if (lastFailure != null && now.isBefore(retryAt)) {
                throw new SasSigningKeyUnavailableException("User delegation key unavailable until " + retryAt,
                        lastFailure);
            }
            return fetch(validity, now);
        }
    }

    private UserDelegationKey fetch(SasValidity validity, Instant now) {
        try {
            var key = keyFetcher.fetch(
                    OffsetDateTime.ofInstant(validity.start(), ZoneOffset.UTC),
                    OffsetDateTime.ofInstant(validity.expiry(), ZoneOffset.UTC));
            if (key == null) {
                throw new IllegalStateException("Blob Storage returned no user delegation key");
            }
            lastFailure = null;
            previous = current;
            current = new CachedKey(validity, key);
            log.info("Fetched the user delegation key for media SAS: keyStart={}, keyExpiry={}",
                    validity.start(), validity.expiry());
            return key;
        } catch (RuntimeException exception) {
            lastFailure = exception;
            retryAt = now.plus(FETCH_RETRY_INTERVAL);
            log.warn("Fetching the user delegation key for media SAS failed: keyStart={}, keyExpiry={}, retryAt={}",
                    validity.start(), validity.expiry(), retryAt, exception);
            throw new SasSigningKeyUnavailableException("User delegation key could not be fetched", exception);
        }
    }

    private record CachedKey(SasValidity validity, UserDelegationKey key) {
    }
}
