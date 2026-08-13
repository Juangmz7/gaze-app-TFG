package com.app.postcommandservice.post.domain.exception;

public class InvalidPostIdException extends RuntimeException {
    public InvalidPostIdException(String message) {
        super(message);
    }
}
