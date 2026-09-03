package com.app.postcommandservice.share.domain.exception;

import java.util.UUID;

import com.app.postcommandservice.shared.domain.exception.DomainException;

public class SelfPostShareNotAllowedException extends DomainException {

    public SelfPostShareNotAllowedException(UUID postId, UUID currentUserId) {
        super(String.format("User %s cannot share their own post %s", currentUserId, postId));
    }
}
