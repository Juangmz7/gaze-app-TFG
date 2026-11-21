package com.app.socialservice.report.domain.model;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.app.socialservice.report.domain.enums.ReportStatus;
import com.app.socialservice.report.domain.enums.UserReportReason;
import com.app.socialservice.report.domain.exception.CannotReportSelfException;
import com.app.socialservice.report.domain.exception.InvalidReportDetailsException;
import com.app.socialservice.report.domain.model.valueobj.ReportDetails;
import com.app.socialservice.report.testutil.UserReportMother;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserReportTest {

    private static final UUID REPORTER_ID = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID REPORTED_ID = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final Instant NOW = Instant.parse("2026-11-19T10:00:00Z");

    @Test
    void shouldOpenAReportWithTrimmedDetails() {
        var report = open(UserReportReason.OTHER, new ReportDetails("  fake account of a celebrity  "));

        assertThat(report.getStatus()).isEqualTo(ReportStatus.OPEN);
        assertThat(report.getDetails().value()).isEqualTo("fake account of a celebrity");
        assertThat(report.getSnapshot()).isEqualTo(UserReportMother.snapshot());
    }

    @Test
    void shouldAcceptAPresetReasonWithoutDetails() {
        var report = open(UserReportReason.HARASSMENT, new ReportDetails(null));

        assertThat(report.getDetails().isEmpty()).isTrue();
    }

    @Test
    void shouldRejectOtherWithoutDetails() {
        assertThatThrownBy(() -> open(UserReportReason.OTHER, new ReportDetails(null)))
                .isInstanceOf(InvalidReportDetailsException.class);
        assertThatThrownBy(() -> open(UserReportReason.OTHER, null))
                .isInstanceOf(InvalidReportDetailsException.class);
    }

    @Test
    void shouldRejectOtherWithBlankDetails() {
        assertThatThrownBy(() -> open(UserReportReason.OTHER, new ReportDetails(" \n\t ")))
                .isInstanceOf(InvalidReportDetailsException.class);
    }

    @Test
    void shouldTreatBlankDetailsAsNone() {
        assertThat(new ReportDetails("   ").value()).isNull();
    }

    @Test
    void shouldAcceptDetailsOfExactlyTheMaximumLengthAfterTrimming() {
        var details = new ReportDetails("  " + "a".repeat(ReportDetails.MAX_LENGTH) + "  ");

        assertThat(details.value()).hasSize(ReportDetails.MAX_LENGTH);
    }

    @Test
    void shouldRejectDetailsOverTheMaximumLength() {
        assertThatThrownBy(() -> new ReportDetails("a".repeat(ReportDetails.MAX_LENGTH + 1)))
                .isInstanceOf(InvalidReportDetailsException.class)
                .hasMessageContaining(String.valueOf(ReportDetails.MAX_LENGTH));
    }

    @Test
    void shouldRejectASelfReport() {
        assertThatThrownBy(() -> UserReport.open(
                UUID.randomUUID(), REPORTER_ID, REPORTER_ID, UserReportReason.SPAM, null,
                UserReportMother.snapshot(), NOW))
                .isInstanceOf(CannotReportSelfException.class);
    }

    @Test
    void shouldRejectASnapshotWithoutUsername() {
        assertThatThrownBy(() -> new UserReportSnapshot(" ", null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static UserReport open(UserReportReason reason, ReportDetails details) {
        return UserReport.open(UUID.randomUUID(), REPORTER_ID, REPORTED_ID, reason, details,
                UserReportMother.snapshot(), NOW);
    }
}
