package com.app.postcommandservice.collab.infrastructure.controller;

import java.util.Set;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import com.app.postcommandservice.post.application.commands.PostMediaCommand;
import com.app.postcommandservice.post.domain.model.valueobj.MediaType;

/**
 * Media item for {@link OpenCollabAndCreatePostRequest}. Unlike the plain single-post
 * creation endpoint (task 33), opening a collab and creating its post still accepts
 * client-supplied {@code url}/{@code thumbnailUrl}/{@code duration} directly and produces an
 * immediately {@code ACCEPTED} post: it does not go through the server-generated-url /
 * upload-SAS / {@code PENDING} flow.
 */
public record OpenCollabAndCreatePostMediaRequest(
        @NotBlank(message = "media url is required")
        String url,
        String thumbnailUrl,
        @NotNull(message = "media mediaType is required")
        MediaType mediaType,
        Integer duration,
        Set<@NotBlank(message = "taggedUsers must not contain blank values") String> taggedUsers,
        @Min(value = 1, message = "media order must be a positive 1-based index")
        int order
) {

    public PostMediaCommand toCommand() {
        return new PostMediaCommand(url, thumbnailUrl, mediaType, duration, taggedUsers, order);
    }
}
