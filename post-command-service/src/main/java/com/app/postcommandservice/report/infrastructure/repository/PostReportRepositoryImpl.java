package com.app.postcommandservice.report.infrastructure.repository;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import com.app.postcommandservice.shared.domain.model.pagination.KeysetPosition;
import com.app.postcommandservice.shared.infrastructure.mapper.JsonMapper;
import com.app.postcommandservice.report.application.repository.PostReportRepository;
import com.app.postcommandservice.report.domain.model.PostReport;
import com.app.postcommandservice.report.domain.model.valueobj.ReportStatus;
import com.app.postcommandservice.report.infrastructure.mapper.ReportMapper;

@Repository
@RequiredArgsConstructor
public class PostReportRepositoryImpl implements PostReportRepository {

    private final PostReportJpaRepository postReportJpaRepository;
    private final ReportMapper reportMapper;
    private final JsonMapper jsonMapper;

    @Override
    public Optional<PostReport> findOpenByReporterAndPost(UUID reporterUserId, UUID postId) {
        return postReportJpaRepository
                .findFirstByReporterUserIdAndPostIdAndStatus(reporterUserId, postId, ReportStatus.OPEN)
                .map(reportMapper::toDomain);
    }

    @Override
    public boolean insertIfNoOpenReport(PostReport report) {
        Objects.requireNonNull(report, "report must not be null");
        var inserted = postReportJpaRepository.insertIfNoOpenReport(
                report.getId(),
                report.getPostId(),
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
    public List<PostReport> findPage(ReportStatus status, KeysetPosition after, int fetchSize) {
        Objects.requireNonNull(status, "status must not be null");
        var rows = after == null
                ? postReportJpaRepository.findFirstPage(status.name(), fetchSize)
                : postReportJpaRepository.findPageAfter(status.name(), after.createdAt(), after.id(), fetchSize);
        return rows.stream().map(reportMapper::toDomain).toList();
    }
}
