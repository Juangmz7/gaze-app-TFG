package com.app.postcommandservice.post.infrastructure.azure;

import java.nio.file.Path;

/**
 * Thin seam over the exact Azure Blob Storage SDK operations {@link AzureMediaVerifier} needs.
 * Mirrors the precedent set by {@link AzureBlobSasClient} (task 32): the Azure SDK client types
 * are final and cannot be mocked under this project's Mockito configuration, so this interface
 * exists purely to keep the verifier unit-testable.
 *
 * <p>Deliberately returns its own small {@link BlobSnapshot} record / byte arrays rather than the
 * SDK's own {@code BlobProperties} (which has no public constructor usable from test code) so unit
 * tests can stub this seam directly.</p>
 *
 * <p>Both download operations take the ETag read by {@link #fetchProperties} and apply it as an
 * {@code If-Match} condition, so every check runs against the exact blob version whose size was
 * validated. If the blob was overwritten in between, Azure answers {@code 412 Precondition
 * Failed}.</p>
 */
interface AzureBlobContentClient {

    /**
     * The blob's content length in bytes and ETag, read from its properties (no content download).
     *
     * @throws com.azure.storage.blob.models.BlobStorageException if the blob does not exist
     *         (status 404) or any other Azure API error occurs
     */
    BlobSnapshot fetchProperties(String blobUrl);

    /**
     * Downloads exactly {@code count} bytes starting at {@code offset}, buffered in memory, only
     * if the blob still matches {@code eTag}. Only ever used for small ranges (magic-byte
     * signature checks).
     *
     * @throws com.azure.storage.blob.models.BlobStorageException with status 404 if the blob no
     *         longer exists, 412 if its ETag no longer matches, or any other Azure API error
     */
    byte[] downloadRange(String blobUrl, String eTag, long offset, long count);

    /**
     * Streams the full blob content directly to {@code destination} on disk, never buffering
     * the whole blob in memory, only if the blob still matches {@code eTag}. {@code destination}
     * must not already exist.
     *
     * @throws com.azure.storage.blob.models.BlobStorageException with status 404 if the blob no
     *         longer exists, 412 if its ETag no longer matches, or any other Azure API error
     */
    void downloadToFile(String blobUrl, String eTag, Path destination);

    /** The subset of a blob's properties the verifier needs. */
    record BlobSnapshot(long contentLength, String eTag) {
    }
}
