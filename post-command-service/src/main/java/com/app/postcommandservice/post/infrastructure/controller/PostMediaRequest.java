package com.app.postcommandservice.post.infrastructure.controller;

import java.util.Set;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import com.app.postcommandservice.post.application.commands.PostMediaCommand;
import com.app.postcommandservice.post.domain.model.valueobj.MediaType;

/**
 * Metadata-only media item for the single-post creation endpoint (task 33). The client no
 * longer supplies {@code url}, {@code thumbnailUrl} or {@code duration}: the server generates
 * the blob urls and returns transient upload SAS urls in the response instead.
 */
public record PostMediaRequest(
        @NotNull(message = "media mediaType is required")
        MediaType mediaType,
        @Min(value = 1, message = "media order must be a positive 1-based index")
        int order,
        Set<@NotBlank(message = "taggedUsers must not contain blank values") String> taggedUsers
) {

    public PostMediaCommand toCommand() {
        return new PostMediaCommand(null, null, mediaType, null, taggedUsers, order);
    }
}
