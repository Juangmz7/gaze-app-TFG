package com.app.postcommandservice.post.infrastructure.controller;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import com.app.postcommandservice.post.application.commands.PostMediaCommand;
import com.app.postcommandservice.post.domain.model.valueobj.MediaType;

public record PostMediaRequest(
        @NotBlank(message = "media url is required")
        String url,
        String thumbnailUrl,
        @NotNull(message = "media mediaType is required")
        MediaType mediaType,
        Integer duration,
        @Min(value = 1, message = "media order must be a positive 1-based index")
        int order
) {

    public PostMediaCommand toCommand() {
        return new PostMediaCommand(url, thumbnailUrl, mediaType, duration, order);
    }
}
