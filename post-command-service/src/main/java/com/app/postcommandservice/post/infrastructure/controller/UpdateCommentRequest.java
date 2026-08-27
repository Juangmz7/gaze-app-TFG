package com.app.postcommandservice.post.infrastructure.controller;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateCommentRequest(
        @NotBlank(message = "content must not be blank")
        @Size(max = 4000, message = "content must be lower than or equal to 4000")
        String content
) {
}
