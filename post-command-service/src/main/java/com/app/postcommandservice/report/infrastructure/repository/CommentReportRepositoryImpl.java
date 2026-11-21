package com.app.postcommandservice.report.infrastructure.repository;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import com.app.postcommandservice.shared.domain.model.pagination.KeysetPosition;
import com.app.postcommandservice.shared.infrastructure.mapper.JsonMapper;
import com.app.postcommandservice.report.application.repository.CommentReportRepository;
import com.app.postcommandservice.report.domain.model.CommentReport;
import com.app.postcommandservice.report.domain.model.valueobj.ReportStatus;
import com.app.postcommandservice.report.infrastructure.mapper.ReportMapper;

@Repository
@RequiredArgsConstructor
public class CommentReportRepositoryImpl implements CommentReportRepository {

    private final CommentReportJpaRepository commentReportJpaRepository;
    private final ReportMapper reportMapper;
    private final JsonMapper jsonMapper;

    @Override
    public Optional<CommentReport> findOpenByReporterAndComment(UUID reporterUserId, UUID commentId) {
        return commentReportJpaRepository
                .findFirstByReporterUserIdAndCommentIdAndStatus(reporterUserId, commentId, ReportStatus.OPEN)
                .map(reportMapper::toDomain);
    }

    @Override
    public boolean insertIfNoOpenReport(CommentReport report) {
        Objects.requireNonNull(report, "report must not be null");
        var inserted = commentReportJpaRepository.insertIfNoOpenReport(
                report.getId(),
                report.getPostId(),
                report.getCommentId(),
                report.getClaim().reporterUserId(),
                report.getClaim().reason().name(),
                report.getClaim().details().value(),
                jsonMapper.toJson(reportMapper.toPayload(report.getSnapshot())),
                report.getStatus().name(),
                report.getCreatedAt()
        );
        return inserted == 1;
    }

    @Override
    public List<CommentReport> findPage(ReportStatus status, KeysetPosition after, int fetchSize) {
        Objects.requireNonNull(status, "status must not be null");
        var rows = after == null
                ? commentReportJpaRepository.findFirstPage(status.name(), fetchSize)
                : commentReportJpaRepository.findPageAfter(status.name(), after.createdAt(), after.id(), fetchSize);
        return rows.stream().map(reportMapper::toDomain).toList();
    }
}
