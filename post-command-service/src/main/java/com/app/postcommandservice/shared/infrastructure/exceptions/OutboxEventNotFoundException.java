package com.app.postcommandservice.shared.infrastructure.exceptions;


public class OutboxEventNotFoundException extends RuntimeException {

    public OutboxEventNotFoundException(String message) {
        super(message);
    }
}

