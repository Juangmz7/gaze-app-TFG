package com.app.postcommandservice.collab.application.dto;

/**
 * {@code joinInstructions} is {@code null} when the collab has no instructions.
 */
public record CollabJoinInstructionsResponse(
        String joinInstructions
) {
}
