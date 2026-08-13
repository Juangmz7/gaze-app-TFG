package com.app.postcommandservice.post.application.dto;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record PostResponse(
        UUID postId,
        UUID userId,
        String description,
        Set<String> taggedUsers,
        Set<String> postTags,
        Instant createdAt,
        Instant updatedAt
) {
}
