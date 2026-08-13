package com.app.postcommandservice.post.domain.exception;

import com.app.postcommandservice.shared.domain.exception.DomainException;

public class TaggedUserBlockedException extends DomainException {

    public TaggedUserBlockedException(String username) {
        super("Tagged user is in a blocked relationship with the post creator: " + username);
    }
}
