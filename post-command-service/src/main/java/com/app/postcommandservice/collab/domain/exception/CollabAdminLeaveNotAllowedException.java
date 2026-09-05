package com.app.postcommandservice.collab.domain.exception;

import java.util.UUID;

import com.app.postcommandservice.shared.domain.exception.DomainException;

public class CollabAdminLeaveNotAllowedException extends DomainException {

    public CollabAdminLeaveNotAllowedException(UUID collabId, UUID userId) {
        super(String.format("Admin user %s cannot leave collab %s", userId, collabId));
    }
}
