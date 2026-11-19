package com.app.postcommandservice.report.domain.model;

import java.util.Objects;
import java.util.UUID;

import com.app.postcommandservice.report.domain.exception.InvalidReportDetailsException;
import com.app.postcommandservice.report.domain.model.valueobj.ReportDetails;
import com.app.postcommandservice.report.domain.model.valueobj.ReportReason;

/** Who reports and why: the part shared by post and comment reports. */
public record ReportClaim(UUID reporterUserId, ReportReason reason, ReportDetails details) {

    public ReportClaim {
        Objects.requireNonNull(reporterUserId, "reporterUserId must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        details = details == null ? new ReportDetails(null) : details;
        if (reason == ReportReason.OTHER && details.isEmpty()) {
            throw new InvalidReportDetailsException("details are required when the reason is OTHER");
        }
    }
}
