package com.app.postcommandservice.share.domain.events;

import java.util.UUID;

import com.app.postcommandservice.shared.domain.events.DomainEvent;

public record PostShareDeletedDomainEvent(UUID id) implements DomainEvent {
}
