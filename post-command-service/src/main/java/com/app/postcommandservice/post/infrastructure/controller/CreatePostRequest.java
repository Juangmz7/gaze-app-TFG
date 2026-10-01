package com.app.postcommandservice.post.infrastructure.controller;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

public record CreatePostRequest(
        @NotNull(message = "correlationId is required")
        UUID correlationId,
        String description,
        @NotEmpty(message = "postTags must contain at least one tag")
        Set<@NotBlank(message = "postTags must not contain blank values") String> postTags,
        @NotEmpty(message = "media must contain at least one item")
        List<@Valid PostMediaRequest> media
) {
}
