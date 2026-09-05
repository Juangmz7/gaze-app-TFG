package com.app.postcommandservice.collab.domain.exception;

import java.util.UUID;

import com.app.postcommandservice.shared.domain.exception.DomainException;

public class CollabJoinRequestBlockedException extends DomainException {

    public CollabJoinRequestBlockedException(UUID collabId, UUID blockedMemberUserId) {
        super(String.format(
                "Cannot request to join collab %s because a block relationship exists with member %s",
                collabId,
                blockedMemberUserId
        ));
    }
}
