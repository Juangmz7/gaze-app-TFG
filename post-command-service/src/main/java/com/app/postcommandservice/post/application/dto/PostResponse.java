package com.app.postcommandservice.post.application.dto;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

import com.app.postcommandservice.post.domain.model.valueobj.PostStatus;
import com.app.postcommandservice.post.domain.model.valueobj.PostType;

public record PostResponse(
        UUID postId,
        UUID userId,
        UUID collabId,
        PostType postType,
        String description,
        Set<String> postTags,
        List<PostMediaResponse> media,
        PostStatus status,
        @JsonInclude(JsonInclude.Include.NON_NULL) Instant uploadExpiresAt,
        Instant createdAt,
        Instant updatedAt
) {
}
