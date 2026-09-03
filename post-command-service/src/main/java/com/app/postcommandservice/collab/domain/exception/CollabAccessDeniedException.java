package com.app.postcommandservice.collab.domain.exception;

import java.util.UUID;

import com.app.postcommandservice.shared.domain.exception.DomainException;

public class CollabAccessDeniedException extends DomainException {

    public CollabAccessDeniedException(UUID collabId, UUID userId) {
        super(String.format("User %s cannot close collab %s", userId, collabId));
    }
}
