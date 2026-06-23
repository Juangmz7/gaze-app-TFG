package com.app.socialservice.follow.application.dto;

import java.time.Instant;
import java.util.UUID;

public record FollowResponse(
        UUID followerId,
        UUID followedId,
        Instant createdAt
) {
}
