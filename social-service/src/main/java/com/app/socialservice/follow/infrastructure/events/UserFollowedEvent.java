package com.app.socialservice.follow.infrastructure.events;

import java.time.Instant;
import java.util.UUID;

import com.app.socialservice.shared.infrastructure.events.EventMessage;
import lombok.Builder;

@Builder
public record UserFollowedEvent(
        UUID id,
        UUID correlationId,
        Instant occurredAt,
        UUID followerUserId,
        UUID followedUserId
) implements EventMessage {
}
