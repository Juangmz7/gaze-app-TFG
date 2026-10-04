package com.app.postcommandservice.post.application.port;

/**
 * Port for deleting a media blob from storage, used by the scheduled media-cleanup job
 * (task 39) to delete the blobs of a post whose upload expired or was already rejected.
 *
 * <p>Implementations must be idempotent: deleting an already-deleted (or never-existing)
 * blob must succeed silently rather than throw, so a retried run after a partial failure is
 * safe.</p>
 */
public interface MediaBlobDeleter {

    /**
     * Deletes {@code blobUrl}'s blob if it exists; a no-op (not an error) if it does not.
     *
     * @throws RuntimeException only for genuine storage infrastructure errors (API
     *         unavailable, timeouts, 5xx, network failures), never because the blob was
     *         already absent.
     */
    void deleteIfExists(String blobUrl);
}
