package com.app.postcommandservice.post.application.port;

import java.time.Instant;

/**
 * Port for signing a plain blob URL (as produced by {@link MediaUrlGenerator}) with a
 * short-lived, write-only SAS so the client can upload directly to Blob Storage.
 *
 * <p>Implementations must never grant read, delete or list permissions, must never
 * persist or log the resulting SAS URL, and must cap the SAS expiry so it never
 * outlives the post's media upload window.</p>
 */
public interface MediaUploadUrlSigner {

    /**
     * Signs {@code blobUrl} with a write-only SAS.
     *
     * @param blobUrl      a plain blob URL previously produced by {@link MediaUrlGenerator}
     * @param postCreatedAt the owning post's creation timestamp; the SAS expiry is
     *                      {@code min(now + upload-sas-ttl, postCreatedAt + upload-window)}
     */
    SignedUploadUrl sign(String blobUrl, Instant postCreatedAt);
}
