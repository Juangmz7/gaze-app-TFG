package com.app.postcommandservice.post.infrastructure.events;

import java.time.Instant;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record UserFollowedEvent(
        UUID id,
        UUID correlationId,
        Instant occurredAt,
        UUID followerUserId,
        UUID followedUserId
) {
}
