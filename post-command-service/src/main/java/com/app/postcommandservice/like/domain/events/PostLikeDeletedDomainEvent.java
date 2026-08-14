package com.app.postcommandservice.like.domain.events;

import java.util.UUID;

import com.app.postcommandservice.shared.domain.events.DomainEvent;

public record PostLikeDeletedDomainEvent(UUID id) implements DomainEvent {
}
