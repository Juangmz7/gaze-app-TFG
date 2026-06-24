package com.app.socialservice.block.domain.exception;

public class SelfUnblockNotAllowedException extends DomainException {
    public SelfUnblockNotAllowedException(String message) {
        super(message);
    }
}
