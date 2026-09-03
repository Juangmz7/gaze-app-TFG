package com.app.postcommandservice.collab.domain.exception;

import java.util.UUID;

import com.app.postcommandservice.shared.domain.exception.DomainException;

public class CollabNotFoundException extends DomainException {

    public CollabNotFoundException(UUID collabId) {
        super(String.format("Collab not found: %s", collabId));
    }
}
