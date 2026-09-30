package com.app.postcommandservice.post.application.dto;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.app.postcommandservice.post.domain.model.valueobj.PostType;

public record PostResponse(
        UUID postId,
        UUID userId,
        UUID collabId,
        PostType postType,
        String description,
        Set<String> taggedUsers,
        Set<String> postTags,
        List<PostMediaResponse> media,
        Instant createdAt,
        Instant updatedAt
) {
}
