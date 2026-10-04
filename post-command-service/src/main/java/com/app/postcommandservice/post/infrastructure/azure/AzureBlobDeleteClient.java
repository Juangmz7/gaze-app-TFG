package com.app.postcommandservice.post.infrastructure.azure;

/**
 * Thin seam over the exact Azure Blob Storage SDK operation {@link AzureMediaBlobDeleter}
 * needs. Mirrors the precedent set by {@link AzureBlobContentClient} (task 36)/{@link
 * AzureBlobSasClient} (task 32): the Azure SDK client types are final and cannot be mocked
 * under this project's Mockito configuration, so this interface exists purely to keep the
 * adapter unit-testable.
 */
interface AzureBlobDeleteClient {

    /**
     * Deletes {@code blobUrl}'s blob if it exists. A no-op if it does not (the Azure SDK's own
     * {@code deleteIfExists} never throws for a missing blob).
     */
    void deleteIfExists(String blobUrl);
}
