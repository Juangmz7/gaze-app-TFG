package com.app.postcommandservice.post.infrastructure.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public record CommentLikeRequest(
        @Valid
        @NotNull(message = "context is required")
        CommentLikeContextRequest context
) {
}
