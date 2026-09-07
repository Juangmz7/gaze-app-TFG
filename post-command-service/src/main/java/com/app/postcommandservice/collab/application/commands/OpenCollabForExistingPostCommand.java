package com.app.postcommandservice.collab.application.commands;

import java.util.UUID;

public record OpenCollabForExistingPostCommand(
        UUID postId,
        UUID correlationId,
        UUID currentUserId,
        String title
) {
}
