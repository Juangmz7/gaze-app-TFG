package com.app.postcommandservice.post.infrastructure.controller;

import java.util.Set;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

public record UpdatePostRequest(
        @NotNull(message = "postId is required")
        UUID postId,
        String description,
        @NotEmpty(message = "postTags must contain at least one tag")
        Set<@NotBlank(message = "postTags must not contain blank values") String> postTags
) {
}
