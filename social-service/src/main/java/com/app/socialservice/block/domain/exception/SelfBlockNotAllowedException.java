package com.app.socialservice.block.domain.exception;

public class SelfBlockNotAllowedException extends DomainException {
    public SelfBlockNotAllowedException(String message) {
        super(message);
    }
}
