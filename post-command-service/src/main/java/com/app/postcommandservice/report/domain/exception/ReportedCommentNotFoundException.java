package com.app.postcommandservice.report.domain.exception;

import java.util.UUID;

import com.app.postcommandservice.shared.domain.exception.DomainException;

/** The reported comment does not exist on that post or is no longer ACTIVE. */
public class ReportedCommentNotFoundException extends DomainException {

    public ReportedCommentNotFoundException(UUID commentId) {
        super(String.format("Comment not found: %s", commentId));
    }
}
