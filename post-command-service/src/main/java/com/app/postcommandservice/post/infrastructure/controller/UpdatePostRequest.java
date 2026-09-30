package com.app.postcommandservice.post.infrastructure.controller;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdatePostRequest(
        @NotNull(message = "postId is required")
        UUID postId,
        @Size(max = 255, message = "title must be lower than or equal to 255")
        String title,
        String description,
        Set<@NotBlank(message = "taggedUsers must not contain blank values") String> taggedUsers,
        @NotEmpty(message = "postTags must contain at least one tag")
        Set<@NotBlank(message = "postTags must not contain blank values") String> postTags,
        List<@Valid PostMediaRequest> media
) {
}
