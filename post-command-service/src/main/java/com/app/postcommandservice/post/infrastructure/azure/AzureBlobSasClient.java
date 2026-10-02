package com.app.postcommandservice.post.infrastructure.azure;

import java.time.OffsetDateTime;

import com.azure.storage.blob.models.UserDelegationKey;
import com.azure.storage.blob.sas.BlobServiceSasSignatureValues;

/**
 * Thin seam over the exact Azure Blob Storage SDK operations {@link AzureMediaUploadUrlSigner}
 * needs. The Azure SDK client types ({@code BlobServiceClient}, {@code BlobContainerClient},
 * {@code BlobClient}) are final and cannot be mocked under this project's Mockito
 * {@code mock-maker-subclass} configuration, so this interface exists purely to keep the
 * signer unit-testable without weakening test isolation or touching shared Mockito config.
 */
interface AzureBlobSasClient {

    /**
     * Account-key service SAS for {@code blobName} (local/Azurite only).
     */
    String generateAccountKeySas(String blobName, BlobServiceSasSignatureValues sasValues);

    /**
     * Requests a fresh user delegation key, valid between {@code start} and {@code expiry}.
     * This is a network call: callers are responsible for caching the result.
     */
    UserDelegationKey fetchUserDelegationKey(OffsetDateTime start, OffsetDateTime expiry);

    /**
     * User delegation SAS for {@code blobName}, signed with an already-obtained delegation key.
     */
    String generateUserDelegationSas(String blobName, BlobServiceSasSignatureValues sasValues, UserDelegationKey key);
}
