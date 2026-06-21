package com.app.socialservice.user.domain.events;

import com.app.socialservice.shared.domain.events.DomainEvent;
import com.app.socialservice.user.domain.model.valueobj.UserId;

import java.time.Instant;
import java.util.UUID;

public record UserDeletedDomainEvent(
        UUID id,
        UserId userId,
        Instant occurredOn
) implements DomainEvent {
}
