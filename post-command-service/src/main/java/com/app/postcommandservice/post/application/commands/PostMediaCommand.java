package com.app.postcommandservice.post.application.commands;

import java.util.Set;

import com.app.postcommandservice.post.domain.model.valueobj.MediaType;

public record PostMediaCommand(
        String url,
        String thumbnailUrl,
        MediaType mediaType,
        Integer duration,
        Set<String> taggedUsers,
        int order
) {
}
