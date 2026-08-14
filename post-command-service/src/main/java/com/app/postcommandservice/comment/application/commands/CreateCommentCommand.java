package com.app.postcommandservice.comment.application.commands;

import java.util.UUID;

public record CreateCommentCommand(
        UUID postId,
        UUID currentUserId,
        String content,
        UUID replyTo
) {
}
