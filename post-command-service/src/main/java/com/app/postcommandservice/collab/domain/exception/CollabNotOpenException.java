package com.app.postcommandservice.collab.domain.exception;

import java.util.UUID;

import com.app.postcommandservice.collab.domain.model.valueobj.ColabStatus;
import com.app.postcommandservice.shared.domain.exception.DomainException;

public class CollabNotOpenException extends DomainException {

    public CollabNotOpenException(UUID collabId, ColabStatus status) {
        super(String.format("Collab %s must be OPEN to receive join requests, current status: %s", collabId, status));
    }
}
