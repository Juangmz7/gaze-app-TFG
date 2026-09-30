package com.app.postcommandservice.post.application.commands;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public record UpdatePostCommand(
        UUID postId,
        UUID currentUserId,
        String title,
        String description,
        Set<String> taggedUsers,
        Set<String> postTags,
        List<PostMediaCommand> media
) {
}
