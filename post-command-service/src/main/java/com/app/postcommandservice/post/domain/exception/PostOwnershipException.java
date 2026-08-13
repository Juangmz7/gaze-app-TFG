package com.app.postcommandservice.post.domain.exception;

import java.util.UUID;

import com.app.postcommandservice.shared.domain.exception.DomainException;

public class PostOwnershipException extends DomainException {

    public PostOwnershipException(UUID postId, UUID userId) {
        super(String.format("User %s cannot modify post %s", userId, postId));
    }
}
