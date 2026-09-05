package com.app.postcommandservice.collab.domain.exception;

import com.app.postcommandservice.shared.domain.exception.DomainException;

public class InvalidCollabMemberBanException extends DomainException {

    public InvalidCollabMemberBanException(String message) {
        super(message);
    }
}
