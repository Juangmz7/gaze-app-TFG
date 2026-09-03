package com.app.postcommandservice.collab.infrastructure.events;

import java.time.Instant;
import java.util.UUID;

import lombok.Builder;

import com.app.postcommandservice.collab.domain.model.valueobj.ColabStatus;
import com.app.postcommandservice.shared.infrastructure.events.EventMessage;

@Builder
public record CollabClosedEvent(
        UUID id,
        UUID correlationId,
        Instant occurredAt,
        UUID collabId,
        String title,
        UUID createdBy,
        UUID closedBy,
        ColabStatus collabStatus,
        Instant collabCreatedAt
) implements EventMessage {
}
