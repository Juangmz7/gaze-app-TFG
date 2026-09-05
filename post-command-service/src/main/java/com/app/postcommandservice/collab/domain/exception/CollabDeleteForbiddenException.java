package com.app.postcommandservice.collab.domain.exception;

import java.util.UUID;

import com.app.postcommandservice.shared.domain.exception.DomainException;

public class CollabDeleteForbiddenException extends DomainException {

    public CollabDeleteForbiddenException(UUID collabId, UUID userId) {
        super(String.format("User %s cannot delete collab %s", userId, collabId));
    }
}
