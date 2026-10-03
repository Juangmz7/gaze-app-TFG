package com.app.postcommandservice.post.application.port;

import java.util.List;

/**
 * Verifies that media blobs referenced by a post were actually uploaded, are valid, within
 * size/format limits, match their declared {@code MediaType}, and (for VIDEO) extracts the
 * duration. Implementations perform no database access and must not be transactional.
 *
 * <p>Business inconsistencies (missing blob, wrong type, too large, corrupt file, unsupported
 * format, unreadable/too-long duration) are reported as a {@link MediaVerificationResult.Failure},
 * never thrown. Infrastructure errors (the storage backend being unavailable, timeouts, 5xx,
 * network failures) must propagate as exceptions so a message-driven caller can retry.</p>
 */
public interface MediaVerifier {

    /**
     * Verifies every item in {@code media}. Stops at the first failing media item.
     */
    MediaVerificationResult verify(List<MediaToVerify> media);
}
