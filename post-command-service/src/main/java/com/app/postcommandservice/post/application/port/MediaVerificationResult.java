package com.app.postcommandservice.post.application.port;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Outcome of a {@link MediaVerifier} run. Business inconsistencies are always represented as
 * a {@link Failure}, never as a thrown exception; only infrastructure errors (Azure API
 * unavailable, timeouts, network failures) propagate as exceptions out of the verifier.
 */
public sealed interface MediaVerificationResult {

    static MediaVerificationResult success(Map<UUID, Integer> videoDurationsSeconds) {
        return new Success(Map.copyOf(videoDurationsSeconds));
    }

    static MediaVerificationResult failure(
            MediaVerificationFailureReason reason, UUID mediaId, String mediaUrl, String message) {
        return new Failure(
                Objects.requireNonNull(reason, "reason must not be null"),
                Objects.requireNonNull(mediaId, "mediaId must not be null"),
                mediaUrl,
                message);
    }

    /**
     * All submitted media were verified successfully. {@code videoDurationsSeconds} maps each
     * {@code VIDEO} media item's id to its extracted duration in seconds; {@code IMAGE} media
     * never appear in this map.
     */
    record Success(Map<UUID, Integer> videoDurationsSeconds) implements MediaVerificationResult {
        public Success {
            Objects.requireNonNull(videoDurationsSeconds, "videoDurationsSeconds must not be null");
        }
    }

    /**
     * At least one media item failed verification. {@code mediaUrl} is the specific blob URL
     * (content {@code url} or {@code thumbnailUrl}) that failed, which may differ from the
     * media's persisted {@code url} when a VIDEO's thumbnail is the one at fault.
     */
    record Failure(MediaVerificationFailureReason reason, UUID mediaId, String mediaUrl, String message)
            implements MediaVerificationResult {
    }
}
