package com.app.postcommandservice.post.domain.model.valueobj;

import com.app.postcommandservice.post.domain.exception.InvalidPostIdException;

import java.util.UUID;

public record PostId(UUID value) {

    public PostId {
        if (value == null) {
            throw new InvalidPostIdException("Post ID must not be null");
        }
    }
}
