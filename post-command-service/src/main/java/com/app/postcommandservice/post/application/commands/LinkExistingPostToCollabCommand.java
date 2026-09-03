package com.app.postcommandservice.post.application.commands;

import java.util.UUID;

public record LinkExistingPostToCollabCommand(
        UUID postId,
        UUID collabId,
        UUID currentUserId
) {
}
