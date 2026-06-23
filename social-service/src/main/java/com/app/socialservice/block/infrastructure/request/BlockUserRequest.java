package com.app.socialservice.block.infrastructure.request;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record BlockUserRequest(@NotNull UUID blockedUserId) {
}
