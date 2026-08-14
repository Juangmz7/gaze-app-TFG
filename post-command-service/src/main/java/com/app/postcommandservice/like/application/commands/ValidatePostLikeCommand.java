package com.app.postcommandservice.like.application.commands;

import java.time.Instant;
import java.util.UUID;

import com.app.postcommandservice.like.domain.model.PostLikeSource;
import com.app.postcommandservice.shared.infrastructure.events.EventMessage;

public record ValidatePostLikeCommand(
        UUID id,
        UUID correlationId,
        Instant occurredAt,
        UUID postId,
        UUID userId,
        PostLikeSource source,
        int feedPosition
) implements EventMessage {
}
