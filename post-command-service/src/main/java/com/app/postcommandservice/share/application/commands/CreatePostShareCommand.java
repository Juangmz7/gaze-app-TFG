package com.app.postcommandservice.share.application.commands;

import java.time.Instant;
import java.util.UUID;

import com.app.postcommandservice.shared.infrastructure.events.EventMessage;

public record CreatePostShareCommand(
        UUID id,
        UUID correlationId,
        Instant occurredAt,
        UUID postId,
        UUID userId
) implements EventMessage {
}
