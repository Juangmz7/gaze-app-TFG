package com.app.postcommandservice.post.infrastructure.events;

import java.time.Instant;
import java.util.UUID;

import lombok.Builder;

import com.app.postcommandservice.post.application.port.MediaVerificationFailureReason;
import com.app.postcommandservice.shared.infrastructure.events.EventMessage;

/**
 * Published when the async media-upload verification flow (task 37) rejects a post's media.
 * {@code reason} is always a custom, non-sensitive message (e.g. "Upload of post {postId}
 * failed: ... at {url}") — never the raw error text from the underlying storage provider.
 */
@Builder
public record PostMediaUploadValidationFailedEvent(
        UUID id,
        UUID correlationId,
        Instant occurredAt,
        UUID postId,
        MediaVerificationFailureReason reasonCode,
        String reason
) implements EventMessage {
}
