package com.app.socialservice.shared.infrastructure.azure.sas;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.models.UserDelegationKey;
import com.azure.storage.blob.sas.BlobSasPermission;
import com.azure.storage.blob.sas.BlobServiceSasSignatureValues;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.app.socialservice.shared.testutil.AzureStorageTestConstants.CLOUD_ACCOUNT_URL;
import static com.app.socialservice.shared.testutil.AzureStorageTestConstants.CONTAINER;
import static com.app.socialservice.shared.testutil.AzureStorageTestConstants.queryParameters;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserDelegationBlobSasSignerTest {

    private static final String BLOB = "avatars/user/picture.jpg";
    private static final String OBJECT_ID = "6f1a3c1e-0000-4000-8000-000000000001";
    private static final Instant NOW = Instant.parse("2026-11-19T10:00:00Z");
    private static final SasValidity TODAY = new SasValidity(
            Instant.parse("2026-11-18T23:55:00Z"), Instant.parse("2026-11-21T00:00:00Z"));
    private static final SasValidity TOMORROW = new SasValidity(
            Instant.parse("2026-11-19T23:55:00Z"), Instant.parse("2026-11-22T00:00:00Z"));

    private final List<OffsetDateTime[]> fetches = new ArrayList<>();
    private final AtomicReference<RuntimeException> nextFailure = new AtomicReference<>();
    private MutableClock clock;
    private UserDelegationBlobSasSigner signer;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(NOW);
        UserDelegationKeyFetcher fetcher = (start, expiry) -> {
            fetches.add(new OffsetDateTime[] {start, expiry});
            var failure = nextFailure.getAndSet(null);
            if (failure != null) {
                throw failure;
            }
            return delegationKey(start, expiry);
        };
        var containerClient = new BlobServiceClientBuilder()
                .endpoint(CLOUD_ACCOUNT_URL)
                .sasToken("sv=2025-01-05")
                .buildClient()
                .getBlobContainerClient(CONTAINER);
        signer = new UserDelegationBlobSasSigner(containerClient, fetcher, clock);
    }

    @Test
    void shouldFetchOneKeyPerWindowValidityAndSignLocallyWithIt() {
        var first = "?" + signer.sign(BLOB, readValues(TODAY), TODAY);
        var second = "?" + signer.sign("avatars/user/other.jpg", readValues(TODAY), TODAY);

        assertThat(fetches).hasSize(1);
        assertThat(fetches.getFirst()[0].toInstant()).isEqualTo(TODAY.start());
        assertThat(fetches.getFirst()[1].toInstant()).isEqualTo(TODAY.expiry());
        assertThat(queryParameters(first).get("skoid")).isEqualTo(OBJECT_ID);
        assertThat(queryParameters(first).get("sp")).isEqualTo("r");
        assertThat(queryParameters(second).get("sig")).isNotEqualTo(queryParameters(first).get("sig"));
    }

    @Test
    void shouldKeepThePreviousWindowsKeyAfterTheNextOneIsFetched() {
        signer.sign(BLOB, readValues(TODAY), TODAY);
        signer.sign(BLOB, readValues(TOMORROW), TOMORROW);

        signer.sign(BLOB, readValues(TODAY), TODAY);

        assertThat(fetches).hasSize(2);
    }

    @Test
    void shouldNotRetryAFailedFetchBeforeTheRetryInterval() {
        nextFailure.set(new IllegalStateException("entra id is down"));

        assertThatThrownBy(() -> signer.sign(BLOB, readValues(TODAY), TODAY))
                .isInstanceOf(SasSigningKeyUnavailableException.class);
        assertThatThrownBy(() -> signer.sign(BLOB, readValues(TODAY), TODAY))
                .isInstanceOf(SasSigningKeyUnavailableException.class);
        assertThat(fetches).hasSize(1);

        clock.advance(UserDelegationBlobSasSigner.FETCH_RETRY_INTERVAL);
        var signed = "?" + signer.sign(BLOB, readValues(TODAY), TODAY);

        assertThat(fetches).hasSize(2);
        assertThat(queryParameters(signed).get("sig")).isNotBlank();
    }

    @Test
    void shouldRejectInvalidArguments() {
        assertThatThrownBy(() -> signer.sign(" ", readValues(TODAY), TODAY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> signer.sign(BLOB, null, TODAY)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> signer.sign(BLOB, readValues(TODAY), null)).isInstanceOf(NullPointerException.class);
    }

    private static BlobServiceSasSignatureValues readValues(SasValidity validity) {
        return new BlobServiceSasSignatureValues(
                OffsetDateTime.ofInstant(validity.expiry(), ZoneOffset.UTC),
                new BlobSasPermission().setReadPermission(true))
                .setStartTime(OffsetDateTime.ofInstant(validity.start(), ZoneOffset.UTC));
    }

    private static UserDelegationKey delegationKey(OffsetDateTime start, OffsetDateTime expiry) {
        return new UserDelegationKey()
                .setSignedObjectId(OBJECT_ID)
                .setSignedTenantId("6f1a3c1e-0000-4000-8000-000000000002")
                .setSignedStart(start)
                .setSignedExpiry(expiry)
                .setSignedService("b")
                .setSignedVersion("2025-01-05")
                .setValue(Base64.getEncoder().encodeToString("a-local-test-key-of-32-bytes!!!!".getBytes()));
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
