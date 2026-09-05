package com.app.postcommandservice.collab.domain.exception;

import com.app.postcommandservice.shared.domain.exception.DomainException;

public class CollabMemberForbiddenException extends DomainException {

    public CollabMemberForbiddenException(String message) {
        super(message);
    }
}
