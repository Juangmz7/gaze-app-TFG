package com.app.postcommandservice.post.domain.events;

import java.util.UUID;

import com.app.postcommandservice.shared.domain.events.DomainEvent;

public record PostUpdatedDomainEvent(UUID id) implements DomainEvent {
}
