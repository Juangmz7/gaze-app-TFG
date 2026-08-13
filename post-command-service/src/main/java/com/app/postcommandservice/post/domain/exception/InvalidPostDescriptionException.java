package com.app.postcommandservice.post.domain.exception;

public class InvalidPostDescriptionException extends RuntimeException {
    public InvalidPostDescriptionException(String message) {
        super(message);
    }
}
