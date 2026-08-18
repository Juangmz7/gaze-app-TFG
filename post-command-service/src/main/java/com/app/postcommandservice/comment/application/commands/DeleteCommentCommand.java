package com.app.postcommandservice.comment.application.commands;

import java.util.UUID;

public record DeleteCommentCommand(
        UUID postId,
        UUID commentId,
        UUID currentUserId
) {
}
