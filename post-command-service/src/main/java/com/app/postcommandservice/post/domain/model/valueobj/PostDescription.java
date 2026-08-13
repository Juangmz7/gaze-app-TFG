package com.app.postcommandservice.post.domain.model.valueobj;

import com.app.postcommandservice.post.domain.exception.InvalidPostDescriptionException;


public record PostDescription(String value) {

    public PostDescription {
        if (value == null) {
            throw new InvalidPostDescriptionException("Post ID must not be null");
        }
        if (value.length() >= 4000) {
            throw new InvalidPostDescriptionException("Post description must be lower than 4000");
        }
    }
}
