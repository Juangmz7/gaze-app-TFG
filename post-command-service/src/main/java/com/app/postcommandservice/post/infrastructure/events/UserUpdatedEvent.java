package com.app.postcommandservice.post.infrastructure.events;

import java.time.Instant;
import java.util.UUID;

import lombok.Builder;

@Builder
public record UserUpdatedEvent(
        UUID id,
        UUID correlationId,
        Instant occurredAt,

        UUID userId,
        String username,
        String email,
        UserBioEventPayload bio,
        String pictureUrl,
        String accountStatus,
        Instant createdAt,
        Instant updatedAt
){}
