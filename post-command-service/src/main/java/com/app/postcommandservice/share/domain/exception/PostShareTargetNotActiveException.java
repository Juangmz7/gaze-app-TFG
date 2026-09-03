package com.app.postcommandservice.share.domain.exception;

import java.util.UUID;

import com.app.postcommandservice.post.domain.model.valueobj.PostStatus;
import com.app.postcommandservice.shared.domain.exception.DomainException;

public class PostShareTargetNotActiveException extends DomainException {

    public PostShareTargetNotActiveException(UUID postId, PostStatus status) {
        super(String.format("Post %s must be ACTIVE to be shared, but was %s", postId, status));
    }
}
