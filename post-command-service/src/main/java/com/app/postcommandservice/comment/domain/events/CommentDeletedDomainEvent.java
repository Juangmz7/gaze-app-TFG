package com.app.postcommandservice.comment.domain.events;

import java.util.UUID;

import com.app.postcommandservice.shared.domain.events.DomainEvent;

public record CommentDeletedDomainEvent(UUID id) implements DomainEvent {
}
