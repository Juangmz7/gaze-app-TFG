package com.app.postcommandservice.collab.application.commands;

import java.util.UUID;

public record DeclineCollabJoinRequestCommand(
        UUID collabId,
        UUID targetUserId,
        UUID actioningUserId
) {
}
