package com.app.postcommandservice.collab.application.commands;

import java.util.UUID;

public record BanCollabMemberCommand(
        UUID collabId,
        UUID targetUserId,
        UUID actioningUserId
) {
}
