package com.app.postcommandservice.collab.domain.events;

import java.util.UUID;

import com.app.postcommandservice.shared.domain.events.DomainEvent;

public record CollabMemberBannedDomainEvent(UUID id) implements DomainEvent {
}
