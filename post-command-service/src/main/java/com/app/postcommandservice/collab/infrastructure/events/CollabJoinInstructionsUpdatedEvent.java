package com.app.postcommandservice.collab.infrastructure.events;

import java.time.Instant;
import java.util.UUID;

import lombok.Builder;

import com.app.postcommandservice.shared.infrastructure.events.EventMessage;

/**
 * Published when the collab ADMIN sets, replaces or clears the join instructions (task 61).
 * {@code joinInstructions} is serialized as {@code null} when they were cleared.
 */
@Builder
public record CollabJoinInstructionsUpdatedEvent(
        UUID id,
        UUID correlationId,
        Instant occurredAt,
        UUID collabId,
        String joinInstructions
) implements EventMessage {
}
