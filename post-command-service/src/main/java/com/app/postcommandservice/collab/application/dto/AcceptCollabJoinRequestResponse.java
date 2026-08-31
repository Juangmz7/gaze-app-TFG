package com.app.postcommandservice.collab.application.dto;

import java.time.Instant;
import java.util.UUID;

import com.app.postcommandservice.collab.domain.model.valueobj.CollabMemberRole;
import com.app.postcommandservice.collab.domain.model.valueobj.CollabMemberStatus;

public record AcceptCollabJoinRequestResponse(
        UUID collabId,
        UUID userId,
        CollabMemberStatus collabMemberStatus,
        CollabMemberRole role,
        Instant createdAt
) {
}
