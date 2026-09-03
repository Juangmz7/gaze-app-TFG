package com.app.postcommandservice.share.application.commands;

import java.util.UUID;

public record DeletePostShareCommand(
        UUID postId,
        UUID currentUserId
) {
}
