package com.app.postcommandservice.post.application.dto;

import java.util.UUID;

import com.app.postcommandservice.post.domain.model.valueobj.MediaType;

public record PostMediaResponse(
        UUID id,
        String url,
        String thumbnailUrl,
        MediaType mediaType,
        Integer duration,
        int order
) {
}
