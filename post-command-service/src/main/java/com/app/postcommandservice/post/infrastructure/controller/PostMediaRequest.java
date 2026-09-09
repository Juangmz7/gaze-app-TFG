package com.app.postcommandservice.post.infrastructure.controller;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import com.app.postcommandservice.post.domain.model.PostMedia;
import com.app.postcommandservice.post.domain.model.valueobj.MediaType;
import java.util.UUID;

public record PostMediaRequest(UUID id, @NotBlank String url, String thumbnailUrl, @NotNull MediaType mediaType,
                               @Min(1) Integer duration, @NotNull @Min(1) Integer order) {
    public PostMedia toDomain() {
        return new PostMedia(id == null ? UUID.randomUUID() : id, url, thumbnailUrl, mediaType, duration, order);
    }
}
