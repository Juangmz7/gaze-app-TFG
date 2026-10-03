package com.app.postcommandservice.post.infrastructure.azure;

import java.nio.file.Path;

/**
 * Thin seam over the exact Azure Blob Storage SDK operations {@link AzureMediaVerifier} needs.
 * Mirrors the precedent set by {@link AzureBlobSasClient} (task 32): the Azure SDK client types
 * are final and cannot be mocked under this project's Mockito configuration, so this interface
 * exists purely to keep the verifier unit-testable.
 *
 * <p>Deliberately returns primitives/byte arrays rather than the SDK's own {@code BlobProperties}
 * (which has no public constructor usable from test code) so unit tests can stub this seam
 * directly.</p>
 */
interface AzureBlobContentClient {

    /**
     * The blob's content length in bytes, read from its properties (no content download).
     *
     * @throws com.azure.storage.blob.models.BlobStorageException if the blob does not exist
     *         (status 404) or any other Azure API error occurs
     */
    long fetchContentLength(String blobUrl);

    /**
     * Downloads exactly {@code count} bytes starting at {@code offset}, buffered in memory.
     * Only ever used for small ranges (magic-byte signature checks).
     */
    byte[] downloadRange(String blobUrl, long offset, long count);

    /**
     * Streams the full blob content directly to {@code destination} on disk, never buffering
     * the whole blob in memory. {@code destination} must not already exist.
     */
    void downloadToFile(String blobUrl, Path destination);
}
