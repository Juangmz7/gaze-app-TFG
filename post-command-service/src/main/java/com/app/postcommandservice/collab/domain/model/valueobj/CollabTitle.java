package com.app.postcommandservice.collab.domain.model.valueobj;

import com.app.postcommandservice.collab.domain.exception.InvalidCollabTitleException;

public record CollabTitle(String value) {

    private static final int MAX_LENGTH = 255;

    public CollabTitle {
        if (value == null) {
            throw new InvalidCollabTitleException("Collab title must not be blank");
        }
        value = value.trim();
        if (value.isEmpty()) {
            throw new InvalidCollabTitleException("Collab title must not be blank");
        }
        if (value.length() > MAX_LENGTH) {
            throw new InvalidCollabTitleException("Collab title must be lower than or equal to 255");
        }
    }
}
