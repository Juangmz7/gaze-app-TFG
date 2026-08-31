package com.app.postcommandservice.collab.application.commands;

import java.util.UUID;

public record AcceptCollabJoinRequestCommand(
        UUID collabId,
        UUID targetUserId,
        UUID actioningUserId
) {
}
