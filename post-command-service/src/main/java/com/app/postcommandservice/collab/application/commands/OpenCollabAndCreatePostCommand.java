package com.app.postcommandservice.collab.application.commands;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.app.postcommandservice.post.application.commands.PostMediaCommand;

public record OpenCollabAndCreatePostCommand(
        UUID correlationId,
        UUID currentUserId,
        String title,
        String description,
        Set<String> taggedUsers,
        Set<String> postTags,
        List<PostMediaCommand> media
) {
}
