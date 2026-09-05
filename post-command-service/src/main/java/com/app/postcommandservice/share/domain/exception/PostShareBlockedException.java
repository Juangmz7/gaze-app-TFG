package com.app.postcommandservice.share.domain.exception;

import java.util.UUID;

import com.app.postcommandservice.shared.domain.exception.DomainException;

public class PostShareBlockedException extends DomainException {

    public PostShareBlockedException(UUID postId, UUID sharerUserId, UUID ownerUserId) {
        super(String.format(
                "User %s cannot share post %s because a block relationship exists with creator %s",
                sharerUserId,
                postId,
                ownerUserId
        ));
    }
}
