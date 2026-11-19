package com.app.socialservice.report.application.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.socialservice.report.application.commands.ReportUserCommand;
import com.app.socialservice.report.application.dto.ReportedUserResponse;
import com.app.socialservice.report.application.dto.UserReportCreationResult;
import com.app.socialservice.report.application.dto.UserReportEntry;
import com.app.socialservice.report.application.dto.UserReportItemResponse;
import com.app.socialservice.report.application.dto.UserReportReceiptResponse;
import com.app.socialservice.report.application.dto.UserReportSnapshotResponse;
import com.app.socialservice.report.application.repository.UserReportRepository;
import com.app.socialservice.report.domain.enums.ReportStatus;
import com.app.socialservice.report.domain.exception.ReportedUserNotFoundException;
import com.app.socialservice.report.domain.model.UserReport;
import com.app.socialservice.report.domain.model.UserReportSnapshot;
import com.app.socialservice.report.domain.model.valueobj.ReportDetails;
import com.app.socialservice.shared.application.pagination.KeysetPage;
import com.app.socialservice.shared.domain.pagination.KeysetCursor;
import com.app.socialservice.shared.domain.pagination.PageLimit;
import com.app.socialservice.user.application.dto.PublicUserProfileData;
import com.app.socialservice.user.application.repository.UserRepository;

/**
 * User reports (App Store guideline 1.2): users report a profile, ADMIN users list the reports.
 * A block between the two users does not stop a report: blocking and reporting go together.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserReportService {

    private final UserReportRepository userReportRepository;
    private final UserRepository userRepository;

    @Transactional
    public UserReportCreationResult reportUser(ReportUserCommand command) {
        var details = new ReportDetails(command.details());
        UserReport.assertValidClaim(command.reporterUserId(), command.reportedUserId(), command.reason(), details);

        var reportedProfile = findReportableProfile(command.reportedUserId());

        var existing = userReportRepository.findOpenReceipt(command.reporterUserId(), command.reportedUserId());
        if (existing.isPresent()) {
            log.info("User {} already has OPEN report {} on user {}",
                    command.reporterUserId(), existing.get().reportId(), command.reportedUserId());
            return new UserReportCreationResult(existing.get(), false);
        }

        var report = UserReport.open(
                UUID.randomUUID(),
                command.reporterUserId(),
                command.reportedUserId(),
                command.reason(),
                details,
                toSnapshot(reportedProfile),
                Instant.now().truncatedTo(ChronoUnit.MICROS)
        );

        if (userReportRepository.insertIfNoOpenReport(report)) {
            log.info("User report {} opened by user {} on user {}: reason={}",
                    report.getId(), command.reporterUserId(), command.reportedUserId(), command.reason());
            return new UserReportCreationResult(toReceipt(report), true);
        }

        // A concurrent request of the same reporter won the unique OPEN index
        var concurrent = userReportRepository.findOpenReceipt(command.reporterUserId(), command.reportedUserId())
                .orElseThrow(() -> new IllegalStateException(String.format(
                        "OPEN report of user %s on user %s vanished after a unique conflict",
                        command.reporterUserId(), command.reportedUserId())));
        log.info("Concurrent duplicate report of user {} on user {} resolved to report {}",
                command.reporterUserId(), command.reportedUserId(), concurrent.reportId());
        return new UserReportCreationResult(concurrent, false);
    }

    @Transactional(readOnly = true)
    public KeysetPage<UserReportItemResponse> listUserReports(ReportStatus status, String cursor, String limit) {
        var pageLimit = PageLimit.parseOptional(limit);
        var after = KeysetCursor.parseOptional(cursor).orElse(null);

        var rows = userReportRepository.findPage(status, after, pageLimit.lookaheadSize());
        var page = KeysetPage.fromLookahead(rows, pageLimit, UserReportEntry::sortKey)
                .map(UserReportService::toItem);

        log.info("User reports page requested: status={}, items={}, hasNext={}",
                status, page.items().size(), page.nextCursor() != null);
        return page;
    }

    /**
     * Unknown, deleted and banned users cannot be reported, the same users GET /profile/{userId}
     * answers 404 for.
     */
    private PublicUserProfileData findReportableProfile(UUID reportedUserId) {
        return userRepository.findPublicProfileData(reportedUserId)
                .filter(profile -> !profile.banned())
                .orElseThrow(() -> {
                    log.info("Report rejected: user {} not found or not visible", reportedUserId);
                    return new ReportedUserNotFoundException(reportedUserId);
                });
    }

    private static UserReportSnapshot toSnapshot(PublicUserProfileData profile) {
        return new UserReportSnapshot(
                profile.username(),
                profile.description(),
                profile.socialMedia(),
                profile.profilePic()
        );
    }

    private static UserReportReceiptResponse toReceipt(UserReport report) {
        return new UserReportReceiptResponse(report.getId(), report.getStatus(), report.getCreatedAt());
    }

    private static UserReportItemResponse toItem(UserReportEntry entry) {
        var snapshot = entry.snapshot();
        return new UserReportItemResponse(
                entry.reportId(),
                entry.reporterUserId(),
                new ReportedUserResponse(entry.reportedUserId(), entry.reportedUsername(), entry.reportedProfilePic()),
                entry.reason(),
                entry.details(),
                new UserReportSnapshotResponse(
                        snapshot.username(),
                        snapshot.description(),
                        snapshot.socialMedia(),
                        snapshot.profilePic()
                ),
                entry.status(),
                entry.createdAt()
        );
    }
}
