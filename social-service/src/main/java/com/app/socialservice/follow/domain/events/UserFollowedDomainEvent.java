package com.app.socialservice.follow.domain.events;

import java.time.Instant;
import java.util.UUID;

import com.app.socialservice.shared.domain.events.DomainEvent;

public record UserFollowedDomainEvent(
        UUID id,
        UUID followerUserId,
        UUID followedUserId,
        Instant occurredAt
) implements DomainEvent {
}
