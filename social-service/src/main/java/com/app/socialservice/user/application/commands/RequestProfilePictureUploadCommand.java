package com.app.socialservice.user.application.commands;

import java.math.BigDecimal;
import java.util.UUID;

public record RequestProfilePictureUploadCommand(
        UUID userId,
        String contentType,
        BigDecimal sizeBytes
) {
}
