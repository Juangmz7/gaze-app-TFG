package com.app.postcommandservice.report.domain.exception;

import java.util.UUID;

import com.app.postcommandservice.shared.domain.exception.DomainException;

/** The reported post does not exist or is not ACCEPTED (pending, deleted or banned). */
public class ReportedPostNotFoundException extends DomainException {

    public ReportedPostNotFoundException(UUID postId) {
        super(String.format("Post not found: %s", postId));
    }
}
