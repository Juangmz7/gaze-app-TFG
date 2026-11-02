package com.app.postcommandservice.post.application.commands;

import java.util.UUID;

public record ConfirmMediaUploadCommand(
        UUID postId,
        UUID userId
) {
}
