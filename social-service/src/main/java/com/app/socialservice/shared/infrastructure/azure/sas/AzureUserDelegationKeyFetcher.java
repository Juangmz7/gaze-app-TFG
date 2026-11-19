package com.app.socialservice.shared.infrastructure.azure.sas;

import java.time.OffsetDateTime;
import java.util.Objects;

import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.models.UserDelegationKey;

/**
 * Asks Blob Storage for a user delegation key with the service client's token credential
 * (DefaultAzureCredential: AKS Workload Identity in prod). The identity needs Storage Blob
 * Delegator on the account and Storage Blob Data Contributor on the media container.
 */
public class AzureUserDelegationKeyFetcher implements UserDelegationKeyFetcher {

    private final BlobServiceClient blobServiceClient;

    public AzureUserDelegationKeyFetcher(BlobServiceClient blobServiceClient) {
        this.blobServiceClient = Objects.requireNonNull(blobServiceClient, "blobServiceClient must not be null");
    }

    @Override
    public UserDelegationKey fetch(OffsetDateTime start, OffsetDateTime expiry) {
        return blobServiceClient.getUserDelegationKey(start, expiry);
    }
}
