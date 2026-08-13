package com.app.postcommandservice.post.application.commands;

import java.util.Set;
import java.util.UUID;

public record CreatePostCommand(
        UUID correlationId,
        UUID currentUserId,
        String description,
        Set<String> taggedUsers,
        Set<String> postTags
) {
}
