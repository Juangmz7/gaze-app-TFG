package com.app.postcommandservice.post.infrastructure.controller;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import com.app.postcommandservice.post.domain.model.PostMedia;
import com.app.postcommandservice.post.domain.model.valueobj.MediaType;
import java.util.UUID;

public record PostMediaRequest(UUID id, @NotBlank @Pattern(regexp = "https?://\\S+", message = "url must be an absolute HTTP(S) URI") String url,
                               @Pattern(regexp = "https?://\\S+", message = "thumbnailUrl must be an absolute HTTP(S) URI") String thumbnailUrl, @NotNull MediaType mediaType,
                               @Min(1) Integer duration, @NotNull @Min(1) Integer order) {
    public PostMedia toDomain() {
        return new PostMedia(id == null ? UUID.randomUUID() : id, url, thumbnailUrl, mediaType, duration, order);
    }
}
