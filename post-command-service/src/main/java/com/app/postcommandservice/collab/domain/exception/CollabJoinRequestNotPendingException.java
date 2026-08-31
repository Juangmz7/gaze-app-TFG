package com.app.postcommandservice.collab.domain.exception;

import java.util.UUID;

import com.app.postcommandservice.collab.domain.model.valueobj.CollabMemberStatus;
import com.app.postcommandservice.shared.domain.exception.DomainException;

public class CollabJoinRequestNotPendingException extends DomainException {

    public CollabJoinRequestNotPendingException(UUID collabId, UUID userId, CollabMemberStatus status, String action) {
        super(String.format(
                "Collab member %s for collab %s must be PENDING to %s the request, but was %s",
                userId,
                collabId,
                action,
                status
        ));
    }
}
