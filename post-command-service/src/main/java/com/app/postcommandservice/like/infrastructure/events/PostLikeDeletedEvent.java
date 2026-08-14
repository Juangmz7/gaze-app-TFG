package com.app.postcommandservice.like.infrastructure.events;

import java.time.Instant;
import java.util.UUID;

import lombok.Builder;

import com.app.postcommandservice.like.domain.model.PostLikeSource;
import com.app.postcommandservice.shared.infrastructure.events.EventMessage;

@Builder
public record PostLikeDeletedEvent(
        UUID id,
        UUID correlationId,
        Instant occurredAt,
        UUID postId,
        UUID userId,
        PostLikeSource source,
        int feedPosition
) implements EventMessage {
}
