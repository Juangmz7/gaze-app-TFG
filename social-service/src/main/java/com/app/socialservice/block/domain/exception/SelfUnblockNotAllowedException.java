package com.app.socialservice.block.domain.exception;

import com.app.socialservice.shared.domain.exception.DomainException;

public class SelfUnblockNotAllowedException extends DomainException {
    public SelfUnblockNotAllowedException(String message) {
        super(message);
    }
}
