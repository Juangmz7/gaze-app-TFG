package com.app.postcommandservice.collab.domain.exception;

import java.util.UUID;

import com.app.postcommandservice.shared.domain.exception.DomainException;

public class CollabNotFoundException extends DomainException {

    public CollabNotFoundException(UUID collabId) {
        super("Collab not found with id: %s".formatted(collabId));
    }
}
