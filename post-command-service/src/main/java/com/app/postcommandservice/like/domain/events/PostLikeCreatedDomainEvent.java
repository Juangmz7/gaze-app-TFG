package com.app.postcommandservice.like.domain.events;

import java.util.UUID;

import com.app.postcommandservice.shared.domain.events.DomainEvent;

public record PostLikeCreatedDomainEvent(UUID id) implements DomainEvent {
}
