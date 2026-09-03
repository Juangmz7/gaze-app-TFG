package com.app.postcommandservice.collab.domain.events;

import java.util.UUID;

import com.app.postcommandservice.shared.domain.events.DomainEvent;

public record CollabJoinRequestCreatedDomainEvent(UUID id) implements DomainEvent {
}
