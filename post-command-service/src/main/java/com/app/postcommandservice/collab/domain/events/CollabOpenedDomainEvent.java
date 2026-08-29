package com.app.postcommandservice.collab.domain.events;

import java.util.UUID;

import com.app.postcommandservice.shared.domain.events.DomainEvent;

public record CollabOpenedDomainEvent(UUID id) implements DomainEvent {
}
