package com.app.postcommandservice.collab.infrastructure.events;

import java.time.Instant;
import java.util.UUID;

import lombok.Builder;

import com.app.postcommandservice.shared.infrastructure.events.EventMessage;

@Builder
public record CollabDeletedEvent(
        UUID id,
        UUID correlationId,
        UUID collabId,
        UUID actionedBy,
        Instant occurredAt
) implements EventMessage {
}
