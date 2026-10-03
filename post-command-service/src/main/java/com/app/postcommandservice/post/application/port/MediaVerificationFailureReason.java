package com.app.postcommandservice.post.application.port;

/**
 * Business reason a {@link MediaVerifier} rejected a media item. These are never thrown as
 * exceptions: they are carried inside a {@link MediaVerificationResult.Failure}.
 *
 * <p>Task 39 will add {@code UPLOAD_EXPIRED}; out of scope for this enum today.</p>
 */
public enum MediaVerificationFailureReason {
    BLOB_NOT_FOUND,
    EMPTY_BLOB,
    FILE_TOO_LARGE,
    UNSUPPORTED_FORMAT,
    TYPE_MISMATCH,
    CORRUPT_FILE,
    DURATION_UNREADABLE,
    DURATION_TOO_LONG
}
