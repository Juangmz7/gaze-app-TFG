package com.app.postcommandservice.post.application.commands;

import java.util.UUID;

public record DeletePostCommand(
        UUID postId,
        UUID currentUserId
) {
}
