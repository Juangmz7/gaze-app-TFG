package com.app.socialservice.follow.application.dto;

import java.util.UUID;

public record RecommendedFollowCandidate(
        UUID userId,
        long commonConnections
) {
}
