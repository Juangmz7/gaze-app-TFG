package com.app.postcommandservice.post.application.port;

/**
 * Business reason a {@link MediaVerifier} rejected a media item, or (for {@code
 * UPLOAD_EXPIRED}) a reason the scheduled media-cleanup job (task 39) expired a post whose
 * upload was never confirmed. These are never thrown as exceptions: they are carried inside a
 * {@link MediaVerificationResult.Failure} or directly inside {@code
 * PostMediaUploadValidationFailedEvent.reasonCode}.
 */
public enum MediaVerificationFailureReason {
    BLOB_NOT_FOUND,
    EMPTY_BLOB,
    FILE_TOO_LARGE,
    UNSUPPORTED_FORMAT,
    TYPE_MISMATCH,
    CORRUPT_FILE,
    DURATION_UNREADABLE,
    DURATION_TOO_LONG,
    /**
     * The post's media upload was never confirmed within {@code posts.media.upload-window}
     * (task 39), never a {@link MediaVerifier} business failure.
     */
    UPLOAD_EXPIRED
}
