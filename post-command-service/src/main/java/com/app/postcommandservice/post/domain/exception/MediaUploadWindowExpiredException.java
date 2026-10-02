package com.app.postcommandservice.post.domain.exception;

import java.time.Instant;

import com.app.postcommandservice.shared.domain.exception.DomainException;

/**
 * Thrown when signing an upload SAS would require an expiry that has already passed,
 * because the post's media upload window ({@code postCreatedAt + posts.media.upload-window})
 * has already elapsed.
 */
public class MediaUploadWindowExpiredException extends DomainException {
    public MediaUploadWindowExpiredException(Instant postCreatedAt, Instant windowExpiresAt) {
        super("Media upload window expired at " + windowExpiresAt
                + " for post created at " + postCreatedAt);
    }
}
