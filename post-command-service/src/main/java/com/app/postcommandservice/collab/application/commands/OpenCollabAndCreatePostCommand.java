package com.app.postcommandservice.collab.application.commands;

import java.util.Set;
import java.util.UUID;

public record OpenCollabAndCreatePostCommand(
        UUID correlationId,
        UUID currentUserId,
        String title,
        String description,
        Set<String> taggedUsers,
        Set<String> postTags
) {
}
