package com.app.postcommandservice.shared.domain.events;

import java.util.UUID;

public record OutboxEventCreatedDomainEvent(UUID id) implements DomainEvent {
}
