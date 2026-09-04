package com.app.postcommandservice.collab.application.commands;

import java.util.UUID;

public record CloseCollabCommand(
        UUID collabId,
        UUID currentUserId
) {
}
