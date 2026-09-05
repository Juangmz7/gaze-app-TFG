package com.app.postcommandservice.collab.application.dto;

import java.time.Instant;
import java.util.UUID;

import com.app.postcommandservice.collab.domain.model.valueobj.ColabStatus;

public record CollabResponse(
        UUID collabId,
        String title,
        UUID createdBy,
        ColabStatus collabStatus,
        Instant createdAt
) {
}
