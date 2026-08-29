package com.app.postcommandservice.commentlike.domain.events;

import java.util.UUID;

import com.app.postcommandservice.shared.domain.events.DomainEvent;

public record PostCommentLikeDeletedDomainEvent(UUID id) implements DomainEvent {
}
