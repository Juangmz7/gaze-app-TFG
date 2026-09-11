package com.app.postcommandservice.post.infrastructure.controller;

import java.util.Set;
import java.util.UUID;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdatePostRequest(
        @NotNull(message = "postId is required")
        UUID postId,
        String description,
        Set<@NotBlank(message = "taggedUsers must not contain blank values") String> taggedUsers,
        Set<@NotBlank(message = "postTags must not contain blank values") String> postTags,
        @Size(max = 255, message = "title must not exceed 255 characters") String title,
        List<@NotNull @jakarta.validation.Valid PostMediaRequest> media
) {
}
