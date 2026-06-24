package com.app.socialservice.block.domain.events;

import java.time.Instant;
import java.util.UUID;

import com.app.socialservice.shared.domain.events.DomainEvent;

public record UserUnblockedDomainEvent(
        UUID id,
        UUID blockerUserId,
        UUID blockedUserId,
        Instant occurredAt
) implements DomainEvent {

    public UserUnblockedDomainEvent {
        if (id == null) {
            throw new IllegalArgumentException("id must not be null");
        }
        if (blockerUserId == null) {
            throw new IllegalArgumentException("blockerUserId must not be null");
        }
        if (blockedUserId == null) {
            throw new IllegalArgumentException("blockedUserId must not be null");
        }
        if (occurredAt == null) {
            throw new IllegalArgumentException("occurredAt must not be null");
        }
    }
}
