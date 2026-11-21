package com.app.postcommandservice.report.domain.model.valueobj;

import com.app.postcommandservice.report.domain.exception.InvalidReportDetailsException;

/**
 * Free text attached to a report. Trimmed; a blank text counts as no details. Whether it is
 * required depends on the reason (see {@code ReportClaim}).
 */
public record ReportDetails(String value) {

    public static final int MAX_LENGTH = 1000;

    public ReportDetails {
        if (value != null) {
            value = value.trim();
            if (value.isEmpty()) {
                value = null;
            } else if (value.length() > MAX_LENGTH) {
                throw new InvalidReportDetailsException(
                        "details must be at most " + MAX_LENGTH + " characters");
            }
        }
    }

    public boolean isEmpty() {
        return value == null;
    }
}
