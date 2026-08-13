package com.app.postcommandservice.post.domain.model.valueobj;

import com.app.postcommandservice.post.domain.exception.InvalidPostDescriptionException;

public record PostDescription(String value) {

    private static final int MAX_LENGTH = 4000;

    public PostDescription {
        if (value == null) {
            throw new InvalidPostDescriptionException("Post description must not be null");
        }
        if (value.length() > MAX_LENGTH) {
            throw new InvalidPostDescriptionException("Post description must be lower than or equal to 4000");
        }
    }
}
