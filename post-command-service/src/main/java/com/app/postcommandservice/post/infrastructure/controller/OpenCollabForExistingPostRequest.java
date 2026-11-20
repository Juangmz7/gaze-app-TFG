package com.app.postcommandservice.post.infrastructure.controller;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * {@code joinInstructions} is optional (task 61): absent, {@code null} or blank means none.
 */
public record OpenCollabForExistingPostRequest(
        @NotNull(message = "correlationId is required")
        UUID correlationId,
        @NotBlank(message = "title is required")
        String title,
        String joinInstructions
) {
}
