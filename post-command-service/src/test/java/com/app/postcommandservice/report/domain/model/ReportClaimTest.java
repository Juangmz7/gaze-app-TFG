package com.app.postcommandservice.report.domain.model;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.app.postcommandservice.report.domain.exception.InvalidReportDetailsException;
import com.app.postcommandservice.report.domain.model.valueobj.ReportDetails;
import com.app.postcommandservice.report.domain.model.valueobj.ReportReason;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReportClaimTest {

    private static final UUID REPORTER_ID = UUID.randomUUID();

    @Test
    void shouldRejectOtherWithoutDetails() {
        assertThatThrownBy(() -> new ReportClaim(REPORTER_ID, ReportReason.OTHER, new ReportDetails(null)))
                .isInstanceOf(InvalidReportDetailsException.class)
                .hasMessageContaining("OTHER");
    }

    @Test
    void shouldRejectOtherWithBlankDetails() {
        assertThatThrownBy(() -> new ReportClaim(REPORTER_ID, ReportReason.OTHER, new ReportDetails("   \n ")))
                .isInstanceOf(InvalidReportDetailsException.class);
    }

    @Test
    void shouldRejectDetailsOverOneThousandCharacters() {
        var tooLong = "a".repeat(ReportDetails.MAX_LENGTH + 1);

        assertThatThrownBy(() -> new ReportDetails(tooLong))
                .isInstanceOf(InvalidReportDetailsException.class)
                .hasMessageContaining("1000");
    }

    @Test
    void shouldAcceptOneThousandCharactersAfterTrimming() {
        var details = new ReportDetails("  " + "a".repeat(ReportDetails.MAX_LENGTH) + "  ");

        assertThat(details.value()).hasSize(ReportDetails.MAX_LENGTH);
    }

    @Test
    void shouldTrimDetailsAndTreatBlankAsAbsent() {
        assertThat(new ReportDetails("  spam link  ").value()).isEqualTo("spam link");
        assertThat(new ReportDetails("   ").isEmpty()).isTrue();
    }

    @Test
    void shouldAllowPresetReasonWithoutDetails() {
        var claim = new ReportClaim(REPORTER_ID, ReportReason.SPAM, null);

        assertThat(claim.details().isEmpty()).isTrue();
        assertThat(claim.reason()).isEqualTo(ReportReason.SPAM);
    }

    @Test
    void shouldAcceptOtherWithDetails() {
        var claim = new ReportClaim(REPORTER_ID, ReportReason.OTHER, new ReportDetails(" scam "));

        assertThat(claim.details().value()).isEqualTo("scam");
    }

    @Test
    void shouldRejectMissingReasonOrReporter() {
        assertThatThrownBy(() -> new ReportClaim(REPORTER_ID, null, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ReportClaim(null, ReportReason.SPAM, null)).isInstanceOf(NullPointerException.class);
    }
}
