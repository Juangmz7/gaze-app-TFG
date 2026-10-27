package com.app.postcommandservice.post.application.dto;

import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

import com.app.postcommandservice.post.domain.model.valueobj.MediaType;

public record PostMediaResponse(
        UUID id,
        String url,
        String thumbnailUrl,
        MediaType mediaType,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer duration,
        Set<String> taggedUsers,
        int order,
        @JsonInclude(JsonInclude.Include.NON_NULL) String uploadUrl,
        @JsonInclude(JsonInclude.Include.NON_NULL) String thumbnailUploadUrl
) {
}
