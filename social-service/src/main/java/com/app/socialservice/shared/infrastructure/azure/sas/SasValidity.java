package com.app.socialservice.shared.infrastructure.azure.sas;

import java.time.Instant;
import java.util.Objects;

/**
 * When a SAS, and the user delegation key that signs it, is valid.
 */
public record SasValidity(Instant start, Instant expiry) {

    public SasValidity {
        Objects.requireNonNull(start, "start must not be null");
        Objects.requireNonNull(expiry, "expiry must not be null");
        if (!expiry.isAfter(start)) {
            throw new IllegalArgumentException("expiry must be after start");
        }
    }
}
