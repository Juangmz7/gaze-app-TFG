package com.app.socialservice.shared.infrastructure.azure.sas;

import java.time.OffsetDateTime;

import com.azure.storage.blob.models.UserDelegationKey;

/**
 * Fetches a user delegation key from Blob Storage. A network call: callers cache the result.
 */
public interface UserDelegationKeyFetcher {

    UserDelegationKey fetch(OffsetDateTime start, OffsetDateTime expiry);
}
