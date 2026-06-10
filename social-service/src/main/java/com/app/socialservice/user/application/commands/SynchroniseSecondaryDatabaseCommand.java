package com.app.socialservice.user.application.commands;

import java.util.UUID;

public record SynchroniseSecondaryDatabaseCommand (
        UUID correlationId,
        UUID eventId,
        UUID userId
) {
}
