package com.app.postcommandservice.post.infrastructure.events;

import java.util.Set;
import java.util.UUID;

import lombok.Builder;

import com.fasterxml.jackson.annotation.JsonInclude;

import com.app.postcommandservice.post.domain.model.valueobj.MediaType;

@Builder
public record PostMediaEventPayload(
        UUID id,
        String url,
        String thumbnailUrl,
        MediaType mediaType,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer duration,
        Set<String> taggedUsers,
        int order
) {
}
