package com.app.postcommandservice.collab.infrastructure.controller;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

public record OpenCollabAndCreatePostRequest(
        @NotNull(message = "correlationId is required")
        UUID correlationId,
        @NotBlank(message = "title is required")
        String title,
        String description,
        Set<@NotBlank(message = "postTags must not contain blank values") String> postTags,
        @NotEmpty(message = "media must contain at least one item")
        List<@Valid OpenCollabAndCreatePostMediaRequest> media
) {
}
