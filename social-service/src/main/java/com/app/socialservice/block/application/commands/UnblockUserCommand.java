package com.app.socialservice.block.application.commands;

import java.util.UUID;

public record UnblockUserCommand(UUID unblockerUserId, UUID unblockedUserId) {

    public UnblockUserCommand {
        if (unblockerUserId == null) {
            throw new IllegalArgumentException("unblockerUserId must not be null");
        }
        if (unblockedUserId == null) {
            throw new IllegalArgumentException("unblockedUserId must not be null");
        }
    }
}
