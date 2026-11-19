package com.app.socialservice.report.domain.model.valueobj;

import com.app.socialservice.report.domain.exception.InvalidReportDetailsException;

/**
 * Free text attached to a report. Trimmed; a blank text counts as no details. Whether it is
 * required depends on the reason (see UserReport.open).
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
                        String.format("details must be at most %d characters", MAX_LENGTH));
            }
        }
    }

    public boolean isEmpty() {
        return value == null;
    }
}
