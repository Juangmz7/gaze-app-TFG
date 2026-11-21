package com.app.socialservice.report.domain.exception;

import java.util.UUID;

import com.app.socialservice.shared.domain.exception.DomainException;

public class ReportedUserNotFoundException extends DomainException {

    public ReportedUserNotFoundException(UUID userId) {
        super("User not found: " + userId);
    }
}
