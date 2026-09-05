package com.app.postcommandservice.collab.domain.events;

import java.util.UUID;

import com.app.postcommandservice.shared.domain.events.DomainEvent;

public record CollabMemberLeftDomainEvent(UUID id) implements DomainEvent {
}
