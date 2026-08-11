package com.app.postcommandservice.shared.infrastructure.exceptions;


public class EventPublisherNotFound extends RuntimeException {

    public EventPublisherNotFound(String message) {
        super(message);
    }
}

