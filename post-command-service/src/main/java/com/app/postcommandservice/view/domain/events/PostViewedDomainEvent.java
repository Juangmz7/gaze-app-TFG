package com.app.postcommandservice.view.domain.events;

import java.util.UUID;

import com.app.postcommandservice.shared.domain.events.DomainEvent;

public record PostViewedDomainEvent(UUID id) implements DomainEvent {
}
