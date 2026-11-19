package com.app.postcommandservice.report.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.app.postcommandservice.report.domain.exception.CannotReportOwnContentException;
import com.app.postcommandservice.report.domain.model.valueobj.ReportReason;
import com.app.postcommandservice.report.domain.model.valueobj.ReportStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReportOpeningTest {

    private static final UUID AUTHOR_ID = UUID.randomUUID();
    private static final UUID REPORTER_ID = UUID.randomUUID();
    private static final UUID POST_ID = UUID.randomUUID();
    private static final UUID COMMENT_ID = UUID.randomUUID();

    @Test
    void shouldOpenPostReportWithSnapshot() {
        var snapshot = new PostReportSnapshot("caption", List.of("https://acc/c/blob-1"), AUTHOR_ID);

        var report = PostReport.open(UUID.randomUUID(), POST_ID,
                new ReportClaim(REPORTER_ID, ReportReason.SPAM, null), snapshot, Instant.now());

        assertThat(report.getStatus()).isEqualTo(ReportStatus.OPEN);
        assertThat(report.getSnapshot()).isEqualTo(snapshot);
    }

    @Test
    void shouldRejectReportingOwnPost() {
        var snapshot = new PostReportSnapshot("caption", List.of(), AUTHOR_ID);

        assertThatThrownBy(() -> PostReport.open(UUID.randomUUID(), POST_ID,
                new ReportClaim(AUTHOR_ID, ReportReason.SPAM, null), snapshot, Instant.now()))
                .isInstanceOf(CannotReportOwnContentException.class);
    }

    @Test
    void shouldRejectReportingOwnComment() {
        var snapshot = new CommentReportSnapshot("text", AUTHOR_ID, POST_ID);

        assertThatThrownBy(() -> CommentReport.open(UUID.randomUUID(), POST_ID, COMMENT_ID,
                new ReportClaim(AUTHOR_ID, ReportReason.HARASSMENT, null), snapshot, Instant.now()))
                .isInstanceOf(CannotReportOwnContentException.class);
    }

    @Test
    void shouldOpenCommentReport() {
        var report = CommentReport.open(UUID.randomUUID(), POST_ID, COMMENT_ID,
                new ReportClaim(REPORTER_ID, ReportReason.HARASSMENT, null),
                new CommentReportSnapshot("text", AUTHOR_ID, POST_ID), Instant.now());

        assertThat(report.getStatus()).isEqualTo(ReportStatus.OPEN);
        assertThat(report.getCommentId()).isEqualTo(COMMENT_ID);
    }

    @Test
    void shouldStoreBareMediaUrlsInPostSnapshot() {
        var snapshot = new PostReportSnapshot(
                "caption",
                List.of("https://acc/c/blob-1?sv=2024&sig=secret", "https://acc/c/blob-2#frag", "https://acc/c/blob-3"),
                AUTHOR_ID
        );

        assertThat(snapshot.mediaUrls())
                .containsExactly("https://acc/c/blob-1", "https://acc/c/blob-2", "https://acc/c/blob-3");
    }
}
