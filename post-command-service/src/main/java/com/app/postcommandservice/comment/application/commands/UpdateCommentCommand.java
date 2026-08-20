package com.app.postcommandservice.comment.application.commands;

import java.util.UUID;

public record UpdateCommentCommand(
        UUID postId,
        UUID commentId,
        UUID currentUserId,
        String content
) {
}
