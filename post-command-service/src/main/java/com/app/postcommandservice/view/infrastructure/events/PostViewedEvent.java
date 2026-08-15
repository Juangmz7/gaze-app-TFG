package com.app.postcommandservice.view.infrastructure.events;

import java.time.Instant;
import java.util.UUID;

import lombok.Builder;

import com.app.postcommandservice.shared.infrastructure.events.EventMessage;
import com.app.postcommandservice.view.domain.model.PostViewExitReason;
import com.app.postcommandservice.view.domain.model.PostViewSource;

@Builder
public record PostViewedEvent(
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
        PostViewExitReason exitReason,
        Instant serverTimestamp,
        int replayCount
) implements EventMessage {
}
