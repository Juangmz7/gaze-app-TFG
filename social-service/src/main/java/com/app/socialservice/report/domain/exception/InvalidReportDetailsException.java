package com.app.socialservice.report.domain.exception;

import com.app.socialservice.shared.domain.exception.DomainException;

public class InvalidReportDetailsException extends DomainException {

    public InvalidReportDetailsException(String message) {
        super(message);
    }
}
