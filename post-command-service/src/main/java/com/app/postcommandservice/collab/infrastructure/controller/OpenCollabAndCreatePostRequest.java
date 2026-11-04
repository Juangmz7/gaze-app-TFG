package com.app.postcommandservice.collab.infrastructure.controller;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import com.app.postcommandservice.post.infrastructure.controller.PostMediaRequest;

/**
 * Opening a collab and creating its post uses the same metadata-only media shape as the plain
 * single-post creation endpoint (task 33/34): the client sends only {@code mediaType}/
 * {@code order}/{@code taggedUsers} per media item, never {@code url}/{@code thumbnailUrl}/
 * {@code duration}. The server generates blob urls and returns transient upload SAS urls, just
 * like the plain endpoint.
 */
public record OpenCollabAndCreatePostRequest(
        @NotNull(message = "correlationId is required")
        UUID correlationId,
        @NotBlank(message = "title is required")
        String title,
        String description,
        Set<@NotBlank(message = "postTags must not contain blank values") String> postTags,
        @NotEmpty(message = "media must contain at least one item")
        List<@Valid PostMediaRequest> media
) {
}
