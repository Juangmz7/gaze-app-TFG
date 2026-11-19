package com.app.postcommandservice.post.application.port;

/**
 * Business reason a {@link MediaVerifier} rejected a media item. These are never thrown as
 * exceptions: they are carried inside a {@link MediaVerificationResult.Failure}.
 *
 * <p>{@link #UPLOAD_EXPIRED} is never produced by a {@code MediaVerifier}: it is the reason code
 * the media cleanup job (task 58) publishes when a post's media was not confirmed within the
 * upload window.</p>
 */
public enum MediaVerificationFailureReason {
    BLOB_NOT_FOUND,
    /** The blob was overwritten between reading its properties and downloading its content. */
    BLOB_MODIFIED,
    EMPTY_BLOB,
    FILE_TOO_LARGE,
    UNSUPPORTED_FORMAT,
    TYPE_MISMATCH,
    CORRUPT_FILE,
    DURATION_UNREADABLE,
    DURATION_TOO_LONG,
    /** The media was not confirmed within {@code posts.media.upload-window} of the post's creation. */
    UPLOAD_EXPIRED
}
