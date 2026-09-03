package com.app.postcommandservice.collab.application.dto;

import java.time.Instant;
import java.util.UUID;

import com.app.postcommandservice.collab.domain.model.valueobj.CollabMemberRole;
import com.app.postcommandservice.collab.domain.model.valueobj.CollabMemberStatus;

public record CollabMemberResponse(
        UUID collabId,
        UUID userId,
        CollabMemberStatus status,
        CollabMemberRole role,
        Instant createdAt
) {
}
