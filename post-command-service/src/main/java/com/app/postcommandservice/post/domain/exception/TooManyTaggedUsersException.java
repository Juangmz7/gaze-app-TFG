package com.app.postcommandservice.post.domain.exception;

import com.app.postcommandservice.shared.domain.exception.DomainException;

public class TooManyTaggedUsersException extends DomainException {

    public TooManyTaggedUsersException(int maxTaggedUsers) {
        super("Post media must not tag more than " + maxTaggedUsers + " users");
    }
}
