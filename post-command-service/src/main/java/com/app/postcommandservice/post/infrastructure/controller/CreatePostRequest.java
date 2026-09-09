package com.app.postcommandservice.post.infrastructure.controller;

import java.util.Set;
import java.util.UUID;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreatePostRequest(
        @NotNull(message = "correlationId is required")
        UUID correlationId,
        String description,
        Set<@NotBlank(message = "taggedUsers must not contain blank values") String> taggedUsers,
        Set<@NotBlank(message = "postTags must not contain blank values") String> postTags,
        String title,
        @NotNull(message = "media is required") List<@NotNull @jakarta.validation.Valid PostMediaRequest> media
) {
}
