package com.app.postcommandservice.collab.application.commands;

import java.util.UUID;

/**
 * {@code joinInstructions} is the raw client value: {@code null} or blank clears them.
 */
public record UpdateCollabJoinInstructionsCommand(
        UUID collabId,
        UUID currentUserId,
        String joinInstructions
) {
}
