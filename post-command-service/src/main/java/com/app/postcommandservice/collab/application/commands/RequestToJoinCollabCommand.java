package com.app.postcommandservice.collab.application.commands;

import java.util.UUID;

public record RequestToJoinCollabCommand(
        UUID collabId,
        UUID currentUserId
) {
}
