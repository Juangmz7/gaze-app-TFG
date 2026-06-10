package com.app.socialservice.shared.infrastructure.exceptions;


public class OutboxEventNotFoundException extends RuntimeException {

    public OutboxEventNotFoundException(String message) {
        super(message);
    }
}

