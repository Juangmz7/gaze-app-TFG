package com.app.socialservice.block.application.commands;

import java.util.UUID;

public record BlockUserCommand(UUID blockerUserId, UUID blockedUserId) {

    public BlockUserCommand {
        if (blockerUserId == null) {
            throw new IllegalArgumentException("blockerUserId must not be null");
        }
        if (blockedUserId == null) {
            throw new IllegalArgumentException("blockedUserId must not be null");
        }
    }
}
