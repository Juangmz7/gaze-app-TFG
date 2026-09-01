package com.app.postcommandservice.post.application.commands;

import java.util.UUID;

public record CheckPostCollabLinkStatusCommand(
        UUID postId,
        UUID currentUserId
) {
}
