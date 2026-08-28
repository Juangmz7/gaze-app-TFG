package com.app.postcommandservice.post.infrastructure.controller;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateCommentRequest(
        @NotNull(message = "correlationId is required")
        UUID correlationId,
        @NotBlank(message = "content must not be blank")
        @Size(max = 4000, message = "content must be lower than or equal to 4000")
        String content,
        UUID replyTo
) {
}
