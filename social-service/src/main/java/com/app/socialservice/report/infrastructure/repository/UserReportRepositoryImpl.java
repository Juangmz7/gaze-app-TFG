package com.app.socialservice.report.infrastructure.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import com.app.socialservice.report.application.dto.UserReportEntry;
import com.app.socialservice.report.application.dto.UserReportReceiptResponse;
import com.app.socialservice.report.application.repository.UserReportRepository;
import com.app.socialservice.report.domain.enums.ReportStatus;
import com.app.socialservice.report.domain.enums.UserReportReason;
import com.app.socialservice.report.domain.model.UserReport;
import com.app.socialservice.report.domain.model.UserReportSnapshot;
import com.app.socialservice.report.infrastructure.entity.UserReportSnapshotPayload;
import com.app.socialservice.shared.domain.pagination.KeysetCursor;
import com.app.socialservice.shared.infrastructure.mapper.JsonMapper;

@Repository
@RequiredArgsConstructor
public class UserReportRepositoryImpl implements UserReportRepository {

    private final JpaUserReportRepository jpaUserReportRepository;
    private final JsonMapper jsonMapper;

    @Override
    public Optional<UserReportReceiptResponse> findOpenReceipt(UUID reporterUserId, UUID reportedUserId) {
        validateUserIds(reporterUserId, reportedUserId);

        return jpaUserReportRepository.findOpenReceipt(reporterUserId, reportedUserId)
                .map(row -> new UserReportReceiptResponse(
                        row.getReportId(),
                        ReportStatus.valueOf(row.getStatus()),
                        row.getCreatedAt()
                ));
    }

    @Override
    public boolean insertIfNoOpenReport(UserReport report) {
        if (report == null) {
            throw new IllegalArgumentException("report must not be null");
        }
        var snapshot = report.getSnapshot();
        var inserted = jpaUserReportRepository.insertIfNoOpenReport(
                report.getId(),
                report.getReportedUserId(),
                report.getReporterUserId(),
                report.getReason().name(),
                report.getDetails().value(),
                jsonMapper.toJson(new UserReportSnapshotPayload(
                        snapshot.username(),
                        snapshot.description(),
                        snapshot.socialMedia(),
                        snapshot.profilePic()
                )),
                report.getStatus().name(),
                report.getCreatedAt()
        );
        return inserted > 0;
    }

    @Override
    public List<UserReportEntry> findPage(ReportStatus status, KeysetCursor after, int fetchSize) {
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        if (fetchSize < 1) {
            throw new IllegalArgumentException("fetchSize must be positive");
        }

        var rows = after == null
                ? jpaUserReportRepository.findFirstPage(status.name(), fetchSize)
                : jpaUserReportRepository.findPageAfter(status.name(), after.createdAt(), after.id(), fetchSize);
        return rows.stream()
                .map(this::toEntry)
                .toList();
    }

    private UserReportEntry toEntry(JpaUserReportRepository.UserReportRowProjection row) {
        var snapshot = jsonMapper.fromJson(row.getSnapshot(), UserReportSnapshotPayload.class);
        return new UserReportEntry(
                row.getReportId(),
                row.getReporterUserId(),
                row.getReportedUserId(),
                row.getReportedUsername(),
                row.getReportedProfilePic(),
                UserReportReason.valueOf(row.getReason()),
                row.getDetails(),
                new UserReportSnapshot(
                        snapshot.username(),
                        snapshot.description(),
                        snapshot.socialMedia(),
                        snapshot.profilePic()
                ),
                ReportStatus.valueOf(row.getStatus()),
                row.getCreatedAt()
        );
    }

    private static void validateUserIds(UUID reporterUserId, UUID reportedUserId) {
        if (reporterUserId == null || reportedUserId == null) {
            throw new IllegalArgumentException("reporterUserId and reportedUserId must not be null");
        }
    }
}
