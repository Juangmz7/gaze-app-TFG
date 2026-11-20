package com.app.postcommandservice.collab.infrastructure.controller;

/**
 * Optional field: absent, {@code null} or blank clears the instructions. Trimming and the
 * 4000-character limit are domain rules ({@code CollabJoinInstructions}), not request rules.
 */
public record UpdateCollabJoinInstructionsRequest(
        String joinInstructions
) {
}
