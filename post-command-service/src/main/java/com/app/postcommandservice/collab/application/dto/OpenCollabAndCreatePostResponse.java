package com.app.postcommandservice.collab.application.dto;

import java.time.Instant;
import java.util.UUID;

import com.app.postcommandservice.collab.domain.model.valueobj.ColabStatus;
import com.app.postcommandservice.post.application.dto.PostResponse;

public record OpenCollabAndCreatePostResponse(
        UUID collabId,
        String title,
        UUID createdBy,
        ColabStatus collabStatus,
        Instant createdAt,
        PostResponse post
) {
}
