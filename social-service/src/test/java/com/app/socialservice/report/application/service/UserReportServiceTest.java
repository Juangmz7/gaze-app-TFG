package com.app.socialservice.report.application.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.app.socialservice.report.application.commands.ReportUserCommand;
import com.app.socialservice.report.application.dto.UserReportEntry;
import com.app.socialservice.report.application.dto.UserReportItemResponse;
import com.app.socialservice.report.application.dto.UserReportReceiptResponse;
import com.app.socialservice.report.application.repository.UserReportRepository;
import com.app.socialservice.report.domain.enums.ReportStatus;
import com.app.socialservice.report.domain.enums.UserReportReason;
import com.app.socialservice.report.domain.exception.CannotReportSelfException;
import com.app.socialservice.report.domain.exception.InvalidReportDetailsException;
import com.app.socialservice.report.domain.exception.ReportedUserNotFoundException;
import com.app.socialservice.report.domain.model.UserReport;
import com.app.socialservice.report.testutil.UserReportMother;
import com.app.socialservice.shared.domain.exception.InvalidCursorException;
import com.app.socialservice.shared.domain.exception.InvalidPageLimitException;
import com.app.socialservice.shared.domain.pagination.KeysetCursor;
import com.app.socialservice.shared.domain.pagination.PageLimit;
import com.app.socialservice.user.application.dto.PublicUserProfileData;
import com.app.socialservice.user.application.repository.UserRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserReportServiceTest {

    private static final UUID REPORTER_ID = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID REPORTED_ID = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final Instant NEWEST = Instant.parse("2026-11-19T10:00:00Z");
    private static final PublicUserProfileData REPORTED_PROFILE = new PublicUserProfileData(
            REPORTED_ID,
            UserReportMother.REPORTED_USERNAME,
            UserReportMother.REPORTED_DESCRIPTION,
            UserReportMother.REPORTED_SOCIAL_MEDIA,
            UserReportMother.REPORTED_PICTURE,
            false
    );

    @Mock
    private UserReportRepository userReportRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private UserReportService userReportService;

    @Test
    void shouldOpenANewReportWithASnapshotOfTheReportedProfile() {
        when(userRepository.findPublicProfileData(REPORTED_ID)).thenReturn(Optional.of(REPORTED_PROFILE));
        when(userReportRepository.findOpenReceipt(REPORTER_ID, REPORTED_ID)).thenReturn(Optional.empty());
        when(userReportRepository.insertIfNoOpenReport(any())).thenReturn(true);
        var before = Instant.now().truncatedTo(ChronoUnit.MICROS);

        var result = userReportService.reportUser(command(UserReportReason.OTHER, "  fake account  "));

        var inserted = ArgumentCaptor.forClass(UserReport.class);
        verify(userReportRepository).insertIfNoOpenReport(inserted.capture());
        var report = inserted.getValue();
        assertThat(result.created()).isTrue();
        assertThat(result.receipt())
                .isEqualTo(new UserReportReceiptResponse(report.getId(), ReportStatus.OPEN, report.getCreatedAt()));
        assertThat(report.getReporterUserId()).isEqualTo(REPORTER_ID);
        assertThat(report.getReportedUserId()).isEqualTo(REPORTED_ID);
        assertThat(report.getDetails().value()).isEqualTo("fake account");
        assertThat(report.getSnapshot()).isEqualTo(UserReportMother.snapshot());
        assertThat(report.getCreatedAt()).isBetween(before, Instant.now());
        assertThat(report.getCreatedAt().getNano() % 1_000).isZero();
    }

    @Test
    void shouldReturnTheExistingOpenReportForADuplicate() {
        var existing = new UserReportReceiptResponse(UUID.randomUUID(), ReportStatus.OPEN, NEWEST);
        when(userRepository.findPublicProfileData(REPORTED_ID)).thenReturn(Optional.of(REPORTED_PROFILE));
        when(userReportRepository.findOpenReceipt(REPORTER_ID, REPORTED_ID)).thenReturn(Optional.of(existing));

        var result = userReportService.reportUser(command(UserReportReason.SPAM, null));

        assertThat(result.created()).isFalse();
        assertThat(result.receipt()).isEqualTo(existing);
        verify(userReportRepository, never()).insertIfNoOpenReport(any());
    }

    @Test
    void shouldReturnTheConcurrentOpenReportWhenTheInsertLosesTheUniqueIndex() {
        var concurrent = new UserReportReceiptResponse(UUID.randomUUID(), ReportStatus.OPEN, NEWEST);
        when(userRepository.findPublicProfileData(REPORTED_ID)).thenReturn(Optional.of(REPORTED_PROFILE));
        when(userReportRepository.findOpenReceipt(REPORTER_ID, REPORTED_ID))
                .thenReturn(Optional.empty(), Optional.of(concurrent));
        when(userReportRepository.insertIfNoOpenReport(any())).thenReturn(false);

        var result = userReportService.reportUser(command(UserReportReason.SPAM, null));

        assertThat(result.created()).isFalse();
        assertThat(result.receipt()).isEqualTo(concurrent);
        verify(userReportRepository, times(2)).findOpenReceipt(REPORTER_ID, REPORTED_ID);
    }

    @Test
    void shouldRejectOtherWithoutDetailsBeforeAnyQuery() {
        assertThatThrownBy(() -> userReportService.reportUser(command(UserReportReason.OTHER, null)))
                .isInstanceOf(InvalidReportDetailsException.class);
        assertThatThrownBy(() -> userReportService.reportUser(command(UserReportReason.OTHER, "   ")))
                .isInstanceOf(InvalidReportDetailsException.class);

        verifyNoInteractions(userRepository, userReportRepository);
    }

    @Test
    void shouldRejectDetailsOverOneThousandCharactersBeforeAnyQuery() {
        var tooLong = "x".repeat(1001);

        assertThatThrownBy(() -> userReportService.reportUser(command(UserReportReason.SPAM, tooLong)))
                .isInstanceOf(InvalidReportDetailsException.class);

        verifyNoInteractions(userRepository, userReportRepository);
    }

    @Test
    void shouldRejectASelfReportBeforeAnyQuery() {
        var selfReport = new ReportUserCommand(REPORTER_ID, REPORTER_ID, UserReportReason.SPAM, null);

        assertThatThrownBy(() -> userReportService.reportUser(selfReport))
                .isInstanceOf(CannotReportSelfException.class);

        verifyNoInteractions(userRepository, userReportRepository);
    }

    @Test
    void shouldRejectAnUnknownUser() {
        when(userRepository.findPublicProfileData(REPORTED_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userReportService.reportUser(command(UserReportReason.SPAM, null)))
                .isInstanceOf(ReportedUserNotFoundException.class);

        verifyNoInteractions(userReportRepository);
    }

    @Test
    void shouldRejectABannedUserLikeAnUnknownOne() {
        var banned = new PublicUserProfileData(REPORTED_ID, "banned", null, null, null, true);
        when(userRepository.findPublicProfileData(REPORTED_ID)).thenReturn(Optional.of(banned));

        assertThatThrownBy(() -> userReportService.reportUser(command(UserReportReason.SPAM, null)))
                .isInstanceOf(ReportedUserNotFoundException.class);

        verifyNoInteractions(userReportRepository);
    }

    @Test
    void shouldListTheFirstPageWithANextCursor() {
        var entries = entries(PageLimit.DEFAULT + 1);
        when(userReportRepository.findPage(ReportStatus.OPEN, null, PageLimit.DEFAULT + 1)).thenReturn(entries);

        var page = userReportService.listUserReports(ReportStatus.OPEN, null, null);

        assertThat(page.items()).hasSize(PageLimit.DEFAULT);
        assertThat(page.items()).extracting(UserReportItemResponse::reportId)
                .containsExactlyElementsOf(entries.subList(0, PageLimit.DEFAULT).stream()
                        .map(UserReportEntry::reportId).toList());
        assertThat(KeysetCursor.decode(page.nextCursor())).isEqualTo(entries.get(PageLimit.DEFAULT - 1).sortKey());
        var first = page.items().getFirst();
        assertThat(first.reportedUser().userId()).isEqualTo(entries.getFirst().reportedUserId());
        assertThat(first.reportedUser().username()).isEqualTo(UserReportMother.REPORTED_USERNAME);
        assertThat(first.reportedUser().profilePic()).isEqualTo(UserReportMother.REPORTED_PICTURE);
        assertThat(first.snapshot().socialMedia()).isEqualTo(UserReportMother.REPORTED_SOCIAL_MEDIA);
    }

    @Test
    void shouldPassTheStatusTheDecodedCursorAndTheLookaheadSize() {
        var cursor = new KeysetCursor(NEWEST, UUID.randomUUID());
        when(userReportRepository.findPage(ReportStatus.RESOLVED, cursor, 6)).thenReturn(entries(2));

        var page = userReportService.listUserReports(ReportStatus.RESOLVED, cursor.encode(), "5");

        assertThat(page.items()).hasSize(2);
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void shouldRejectABadCursorOrLimitBeforeAnyQuery() {
        assertThatThrownBy(() -> userReportService.listUserReports(ReportStatus.OPEN, "not-a-cursor", null))
                .isInstanceOf(InvalidCursorException.class);
        assertThatThrownBy(() -> userReportService.listUserReports(ReportStatus.OPEN, null, "51"))
                .isInstanceOf(InvalidPageLimitException.class);

        verifyNoInteractions(userReportRepository);
    }

    private static ReportUserCommand command(UserReportReason reason, String details) {
        return new ReportUserCommand(REPORTER_ID, REPORTED_ID, reason, details);
    }

    private static List<UserReportEntry> entries(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> UserReportMother.openEntry(UUID.randomUUID(), NEWEST.minusSeconds(i)))
                .toList();
    }
}
