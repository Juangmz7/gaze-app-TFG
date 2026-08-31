package com.app.postcommandservice.collab.domain.exception;

import com.app.postcommandservice.shared.domain.exception.DomainException;

public class CollabMemberNotFoundException extends DomainException {

    public CollabMemberNotFoundException(String message) {
        super(message);
    }
}
