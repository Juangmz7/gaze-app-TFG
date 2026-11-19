package com.app.postcommandservice.post.application.port;

/**
 * Deletes media blobs from storage (task 58: blobs of expired or failed uploads). Implementations
 * call the storage backend over the network, so callers must never invoke it inside a database
 * transaction.
 */
public interface MediaBlobDeleter {

    /**
     * Deletes the blob behind {@code blobUrl} if it exists. Idempotent: deleting a missing blob
     * is not an error.
     *
     * @return {@code true} if a blob was deleted, {@code false} if there was none
     * @throws RuntimeException on storage infrastructure errors (unavailable, timeouts, 5xx)
     */
    boolean deleteIfExists(String blobUrl);
}
