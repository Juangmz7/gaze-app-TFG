package com.app.postcommandservice.post.application.dto;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import com.app.postcommandservice.post.domain.model.valueobj.PostType;
import com.app.postcommandservice.post.domain.model.PostMedia;
import java.util.List;

public record PostResponse(
        UUID postId,
        UUID userId,
        UUID collabId,
        PostType postType,
        String description,
        Set<String> taggedUsers,
        Set<String> postTags,
        String title,
        List<PostMedia> media,
        Instant createdAt,
        Instant updatedAt
) {
}
