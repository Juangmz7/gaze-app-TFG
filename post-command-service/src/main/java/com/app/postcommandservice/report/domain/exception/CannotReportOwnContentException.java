package com.app.postcommandservice.report.domain.exception;

import com.app.postcommandservice.shared.domain.exception.DomainException;

public class CannotReportOwnContentException extends DomainException {

    public CannotReportOwnContentException() {
        super("You cannot report your own content");
    }
}
