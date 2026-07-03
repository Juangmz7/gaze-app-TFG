package com.app.socialservice.block.domain.exception;

import com.app.socialservice.shared.domain.exception.DomainException;

public class SelfBlockNotAllowedException extends DomainException {
    public SelfBlockNotAllowedException(String message) {
        super(message);
    }
}
