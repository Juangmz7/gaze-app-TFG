package com.app.postcommandservice.commentlike.domain.events;

import java.util.UUID;

import com.app.postcommandservice.shared.domain.events.DomainEvent;

public record PostCommentLikeCreatedDomainEvent(UUID id) implements DomainEvent {
}
