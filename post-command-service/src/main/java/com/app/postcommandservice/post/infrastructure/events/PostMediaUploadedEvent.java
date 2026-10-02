package com.app.postcommandservice.post.infrastructure.events;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import lombok.Builder;

import com.app.postcommandservice.shared.infrastructure.events.EventMessage;

@Builder
public record PostMediaUploadedEvent(
        UUID id,
        UUID correlationId,
        Instant occurredAt,
        UUID postId,
        List<PostMediaUploadedMediaPayload> media
) implements EventMessage {
}
