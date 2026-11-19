package com.app.socialservice.report.application.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.app.socialservice.report.application.dto.UserReportEntry;
import com.app.socialservice.report.application.dto.UserReportReceiptResponse;
import com.app.socialservice.report.domain.enums.ReportStatus;
import com.app.socialservice.report.domain.model.UserReport;
import com.app.socialservice.shared.domain.pagination.KeysetCursor;

public interface UserReportRepository {

    Optional<UserReportReceiptResponse> findOpenReceipt(UUID reporterUserId, UUID reportedUserId);

    /**
     * false when the reporter already has an OPEN report on that user (the unique partial index
     * of the production schema), including one a concurrent request inserted.
     */
    boolean insertIfNoOpenReport(UserReport report);

    /**
     * Up to fetchSize reports with the given status, newest first; after is null for the first page.
     */
    List<UserReportEntry> findPage(ReportStatus status, KeysetCursor after, int fetchSize);
}
