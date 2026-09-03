package com.app.postcommandservice.share.application.commands;

import java.util.UUID;

public record CreatePostShareCommand(
        UUID postId,
        UUID currentUserId
) {
}
