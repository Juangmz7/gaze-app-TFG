package com.app.postcommandservice.view.application.commands;

import java.time.Instant;
import java.util.UUID;

import com.app.postcommandservice.shared.infrastructure.events.EventMessage;
import com.app.postcommandservice.view.domain.model.PostViewExitReason;
import com.app.postcommandservice.view.domain.model.PostViewSource;

public record ProcessPostViewCommand(
        UUID id,
        UUID correlationId,
        Instant occurredAt,
        UUID viewId,
        UUID postId,
        UUID userId,
        PostViewSource source,
        int feedPosition,
        int durationMs,
        int timeWatchedMs,
        int completionPercent,
        PostViewExitReason exitReason
) implements EventMessage {
}
