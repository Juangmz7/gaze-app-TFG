package com.app.postcommandservice.report.application.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.app.postcommandservice.shared.domain.model.pagination.KeysetPosition;
import com.app.postcommandservice.report.domain.model.CommentReport;
import com.app.postcommandservice.report.domain.model.valueobj.ReportStatus;

public interface CommentReportRepository {

    Optional<CommentReport> findOpenByReporterAndComment(UUID reporterUserId, UUID commentId);

    /**
     * Inserts the report unless the same reporter already has an OPEN report on the comment
     * (enforced atomically by the database). Returns whether a row was inserted.
     */
    boolean insertIfNoOpenReport(CommentReport report);

    /** Rows with {@code status}, newest first, strictly after {@code after} (null = first page). */
    List<CommentReport> findPage(ReportStatus status, KeysetPosition after, int fetchSize);
}
