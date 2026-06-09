package com.app.socialservice.user.domain.exception;

public class InvalidUserIdException extends RuntimeException {

    public InvalidUserIdException(String message) {
        super(message);
    }
}
