package com.app.postcommandservice.collab.domain.exception;

import java.util.UUID;

import com.app.postcommandservice.shared.domain.exception.DomainException;

public class CollabMemberNotFoundException extends DomainException {

    public CollabMemberNotFoundException(UUID collabId, UUID userId) {
        super(String.format("Collab member not found for collab %s and user %s", collabId, userId));
    }
}
