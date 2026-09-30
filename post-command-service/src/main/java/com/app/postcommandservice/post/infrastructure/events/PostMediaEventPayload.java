package com.app.postcommandservice.post.infrastructure.events;

import java.util.UUID;

import lombok.Builder;

import com.app.postcommandservice.post.domain.model.valueobj.MediaType;

@Builder
public record PostMediaEventPayload(
        UUID id,
        String url,
        String thumbnailUrl,
        MediaType mediaType,
        Integer duration,
        int order
) {
}
