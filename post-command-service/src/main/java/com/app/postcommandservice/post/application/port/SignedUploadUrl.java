package com.app.postcommandservice.post.application.port;

import java.time.Instant;

/**
 * A transient, write-only upload URL produced by {@link MediaUploadUrlSigner}.
 * Must never be persisted or logged: it is returned to the client once and discarded.
 */
public record SignedUploadUrl(String url, Instant expiresAt) {
}
