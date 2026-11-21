package com.app.postcommandservice.report.application.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.app.postcommandservice.shared.domain.model.pagination.KeysetPosition;
import com.app.postcommandservice.report.domain.model.PostReport;
import com.app.postcommandservice.report.domain.model.valueobj.ReportStatus;

public interface PostReportRepository {

    Optional<PostReport> findOpenByReporterAndPost(UUID reporterUserId, UUID postId);

    /**
     * Inserts the report unless the same reporter already has an OPEN report on the post
     * (enforced atomically by the database). Returns whether a row was inserted.
     */
    boolean insertIfNoOpenReport(PostReport report);

    /** Rows with {@code status}, newest first, strictly after {@code after} (null = first page). */
    List<PostReport> findPage(ReportStatus status, KeysetPosition after, int fetchSize);
}
