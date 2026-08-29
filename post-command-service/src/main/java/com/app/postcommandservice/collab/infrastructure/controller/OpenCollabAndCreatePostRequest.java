package com.app.postcommandservice.collab.infrastructure.controller;

import java.util.Set;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record OpenCollabAndCreatePostRequest(
        @NotNull(message = "correlationId is required")
        UUID correlationId,
        @NotBlank(message = "title is required")
        String title,
        String description,
        Set<@NotBlank(message = "taggedUsers must not contain blank values") String> taggedUsers,
        Set<@NotBlank(message = "postTags must not contain blank values") String> postTags
) {
}
