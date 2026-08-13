package com.app.postcommandservice.post.domain.exception;

import com.app.postcommandservice.shared.domain.exception.DomainException;

public class TaggedUserNotFoundException extends DomainException {

    public TaggedUserNotFoundException(String username) {
        super("Tagged user not found: " + username);
    }
}
