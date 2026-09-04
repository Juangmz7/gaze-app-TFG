package com.app.postcommandservice.collab.infrastructure.events;

import java.time.Instant;
import java.util.UUID;

import lombok.Builder;

import com.app.postcommandservice.collab.domain.model.valueobj.CollabMemberRole;
import com.app.postcommandservice.collab.domain.model.valueobj.CollabMemberStatus;
import com.app.postcommandservice.shared.infrastructure.events.EventMessage;

@Builder
public record CollabMemberLeftEvent(
        UUID id,
        UUID correlationId,
        Instant occurredAt,
        UUID collabId,
        UUID userId,
        CollabMemberStatus collabMemberStatus,
        CollabMemberRole role,
        Instant createdAt
) implements EventMessage {
}
