package com.app.postcommandservice.post.domain.exception;

import java.util.UUID;

import com.app.postcommandservice.shared.domain.exception.DomainException;

public class PostNotFoundException extends DomainException {

    public PostNotFoundException(UUID postId) {
        super(String.format("Post not found: %s", postId));
    }
}
