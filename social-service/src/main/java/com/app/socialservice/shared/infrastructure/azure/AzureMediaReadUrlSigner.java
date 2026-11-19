package com.app.socialservice.shared.infrastructure.azure;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

import com.azure.storage.blob.sas.BlobSasPermission;
import com.azure.storage.blob.sas.BlobServiceSasSignatureValues;
import com.azure.storage.common.sas.SasProtocol;

import lombok.extern.slf4j.Slf4j;

import com.app.socialservice.shared.application.media.MediaReadUrlSigner;
import com.app.socialservice.shared.infrastructure.azure.sas.BlobSasSigner;
import com.app.socialservice.shared.infrastructure.azure.sas.SasSigningKeyUnavailableException;
import com.app.socialservice.shared.infrastructure.azure.sas.SasValidity;

/**
 * Read-only SAS ({@code sp=r}, {@code sr=b}) on the blob URLs of the media container, with the
 * same rules as post-query-service (task 45). Time is cut into fixed windows of
 * {@code azure.storage.read-sas-window}, counted from the Unix epoch, so every URL signed in
 * one window is byte-identical for the same blob (the app's image cache keys on it):
 *
 * <pre>
 * window start = floor(now / window) * window
 * st           = window start - 5 min (clock skew)
 * se           = window start + 2 * window
 * </pre>
 *
 * HTTPS only, unless the account itself is plain HTTP (Azurite). When the current window's
 * user delegation key cannot be fetched, the previous window's key signs: its SAS is still
 * valid until the end of the current window.
 */
@Slf4j
public class AzureMediaReadUrlSigner implements MediaReadUrlSigner {

    public static final Duration CLOCK_SKEW = Duration.ofMinutes(5);
    public static final Duration MIN_WINDOW = Duration.ofMinutes(1);
    public static final Duration MAX_WINDOW = Duration.ofHours(72);

    private final BlobContainerLocation location;
    private final BlobSasSigner blobSasSigner;
    private final Duration window;
    private final Clock clock;

    public AzureMediaReadUrlSigner(
            BlobContainerLocation location,
            BlobSasSigner blobSasSigner,
            Duration window,
            Clock clock) {
        this.location = Objects.requireNonNull(location, "location must not be null");
        this.blobSasSigner = Objects.requireNonNull(blobSasSigner, "blobSasSigner must not be null");
        this.window = requireWindow(window);
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public String signReadUrl(String url) {
        var blobName = location.blobNameOfBareUrl(url);
        if (blobName.isEmpty()) {
            return url;
        }

        var validity = validityAt(clock.instant());
        String sas;
        try {
            sas = blobSasSigner.sign(blobName.get(), readValues(validity), validity);
        } catch (SasSigningKeyUnavailableException exception) {
            var previous = new SasValidity(validity.start().minus(window), validity.expiry().minus(window));
            log.warn("Signing media read SAS with the previous window's key: windowStart={}",
                    previous.start().plus(CLOCK_SKEW));
            sas = blobSasSigner.sign(blobName.get(), readValues(previous), previous);
        }
        return url + "?" + sas;
    }

    /**
     * The read SAS validity of the window that holds {@code now}.
     */
    public SasValidity validityAt(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        var windowMillis = window.toMillis();
        var windowStart = Instant.ofEpochMilli(Math.floorDiv(now.toEpochMilli(), windowMillis) * windowMillis);
        return new SasValidity(windowStart.minus(CLOCK_SKEW), windowStart.plus(window.multipliedBy(2)));
    }

    private BlobServiceSasSignatureValues readValues(SasValidity validity) {
        return new BlobServiceSasSignatureValues(
                OffsetDateTime.ofInstant(validity.expiry(), ZoneOffset.UTC),
                new BlobSasPermission().setReadPermission(true))
                .setStartTime(OffsetDateTime.ofInstant(validity.start(), ZoneOffset.UTC))
                .setProtocol(location.isPlainHttp() ? SasProtocol.HTTPS_HTTP : SasProtocol.HTTPS_ONLY);
    }

    private static Duration requireWindow(Duration window) {
        Objects.requireNonNull(window, "window must not be null");
        if (window.compareTo(MIN_WINDOW) < 0 || window.compareTo(MAX_WINDOW) > 0) {
            throw new IllegalArgumentException(String.format(
                    "azure.storage.read-sas-window %s must be between %s and %s", window, MIN_WINDOW, MAX_WINDOW));
        }
        return window;
    }
}
