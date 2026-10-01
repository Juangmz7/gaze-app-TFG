package com.app.postcommandservice.post.domain.exception;

import java.util.UUID;

import com.app.postcommandservice.post.domain.model.valueobj.PostStatus;
import com.app.postcommandservice.shared.domain.exception.DomainException;

public class PostNotPendingException extends DomainException {

    public PostNotPendingException(UUID postId, PostStatus status, String action) {
        super(String.format("Post %s must be PENDING to %s, but was %s", postId, action, status));
    }
}
