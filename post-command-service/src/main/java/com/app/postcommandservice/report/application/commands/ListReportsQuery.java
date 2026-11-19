package com.app.postcommandservice.report.application.commands;

import java.util.Objects;

import com.app.postcommandservice.shared.domain.model.pagination.KeysetPosition;
import com.app.postcommandservice.shared.domain.model.pagination.PageLimit;
import com.app.postcommandservice.report.domain.model.valueobj.ReportStatus;

/** {@code after} is null for the first page. */
public record ListReportsQuery(ReportStatus status, KeysetPosition after, PageLimit limit) {

    public ListReportsQuery {
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(limit, "limit must not be null");
    }
}
