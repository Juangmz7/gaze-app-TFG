package com.app.postcommandservice.report.domain.exception;

import com.app.postcommandservice.shared.domain.exception.DomainException;

public class InvalidReportDetailsException extends DomainException {

    public InvalidReportDetailsException(String message) {
        super(message);
    }
}
