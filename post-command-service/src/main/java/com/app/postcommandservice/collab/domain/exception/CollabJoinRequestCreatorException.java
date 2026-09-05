package com.app.postcommandservice.collab.domain.exception;

import java.util.UUID;

import com.app.postcommandservice.shared.domain.exception.DomainException;

public class CollabJoinRequestCreatorException extends DomainException {

    public CollabJoinRequestCreatorException(UUID collabId) {
        super(String.format("Collab creator cannot request to join collab %s", collabId));
    }
}
