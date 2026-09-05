package com.app.postcommandservice.collab.application.commands;

import java.util.UUID;

public record CancelCollabJoinRequestCommand(
        UUID collabId,
        UUID userId
) {
}
