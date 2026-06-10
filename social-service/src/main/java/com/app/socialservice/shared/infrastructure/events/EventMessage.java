package com.app.socialservice.shared.infrastructure.events;

import java.time.Instant;
import java.util.UUID;

public interface EventMessage {
    UUID id();
    UUID correlationId();
    Instant occurredAt();
}
