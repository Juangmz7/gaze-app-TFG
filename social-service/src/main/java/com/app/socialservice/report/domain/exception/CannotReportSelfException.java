package com.app.socialservice.report.domain.exception;

import com.app.socialservice.shared.domain.exception.DomainException;

public class CannotReportSelfException extends DomainException {

    public CannotReportSelfException() {
        super("You cannot report yourself");
    }
}
