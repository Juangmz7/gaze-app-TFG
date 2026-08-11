package com.app.postcommandservice.shared.infrastructure.events;

import java.time.Instant;
import java.util.UUID;

public interface EventMessage {
    UUID id();
    UUID correlationId();
    Instant occurredAt();
}
