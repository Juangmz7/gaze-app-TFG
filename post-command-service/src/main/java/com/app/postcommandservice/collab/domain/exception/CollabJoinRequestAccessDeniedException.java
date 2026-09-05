package com.app.postcommandservice.collab.domain.exception;

import java.util.UUID;

import com.app.postcommandservice.shared.domain.exception.DomainException;

public class CollabJoinRequestAccessDeniedException extends DomainException {

    public CollabJoinRequestAccessDeniedException(UUID collabId, UUID userId) {
        super(String.format(
                "User %s is not an accepted admin for collab %s",
                userId,
                collabId
        ));
    }
}
