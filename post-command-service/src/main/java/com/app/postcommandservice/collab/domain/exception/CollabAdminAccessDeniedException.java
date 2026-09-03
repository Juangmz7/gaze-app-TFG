package com.app.postcommandservice.collab.domain.exception;

import java.util.UUID;

import com.app.postcommandservice.shared.domain.exception.DomainException;

public class CollabAdminAccessDeniedException extends DomainException {

    public CollabAdminAccessDeniedException(UUID collabId, UUID userId) {
        super("User %s is not an accepted admin of collab %s".formatted(userId, collabId));
    }
}
