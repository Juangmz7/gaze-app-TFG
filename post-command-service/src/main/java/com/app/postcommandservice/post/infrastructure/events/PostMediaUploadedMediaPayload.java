package com.app.postcommandservice.post.infrastructure.events;

import java.util.UUID;

import lombok.Builder;

import com.app.postcommandservice.post.domain.model.valueobj.MediaType;

@Builder
public record PostMediaUploadedMediaPayload(
        UUID id,
        String url,
        String thumbnailUrl,
        MediaType mediaType,
        int order
) {
}
