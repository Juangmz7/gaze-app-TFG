package com.app.postcommandservice.report.infrastructure.mapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.app.postcommandservice.report.domain.model.valueobj.ReportReason;
import com.app.postcommandservice.report.domain.model.valueobj.ReportStatus;
import com.app.postcommandservice.report.infrastructure.entity.CommentReportEntity;
import com.app.postcommandservice.report.infrastructure.entity.CommentReportSnapshotPayload;
import com.app.postcommandservice.report.infrastructure.entity.PostReportEntity;
import com.app.postcommandservice.report.infrastructure.entity.PostReportSnapshotPayload;

import static org.assertj.core.api.Assertions.assertThat;

class ReportMapperTest {

    private final ReportMapper mapper = new ReportMapper();

    @Test
    void shouldMapPostReportEntityAndSnapshotBothWays() {
        var authorId = UUID.randomUUID();
        var entity = PostReportEntity.builder()
                .id(UUID.randomUUID())
                .postId(UUID.randomUUID())
                .reporterUserId(UUID.randomUUID())
                .reason(ReportReason.OTHER)
                .details("scam")
                .snapshot(new PostReportSnapshotPayload("caption", List.of("https://acc/c/1"), authorId))
                .status(ReportStatus.DISMISSED)
                .createdAt(Instant.parse("2026-11-19T10:00:00Z"))
                .build();

        var report = mapper.toDomain(entity);

        assertThat(report.getId()).isEqualTo(entity.getId());
        assertThat(report.getClaim().details().value()).isEqualTo("scam");
        assertThat(report.getStatus()).isEqualTo(ReportStatus.DISMISSED);
        assertThat(mapper.toPayload(report.getSnapshot())).isEqualTo(entity.getSnapshot());
    }

    @Test
    void shouldMapCommentReportEntityAndSnapshotBothWays() {
        var postId = UUID.randomUUID();
        var entity = CommentReportEntity.builder()
                .id(UUID.randomUUID())
                .postId(postId)
                .commentId(UUID.randomUUID())
                .reporterUserId(UUID.randomUUID())
                .reason(ReportReason.HATE_SPEECH)
                .snapshot(new CommentReportSnapshotPayload("text", UUID.randomUUID(), postId))
                .status(ReportStatus.OPEN)
                .createdAt(Instant.parse("2026-11-19T10:00:00Z"))
                .build();

        var report = mapper.toDomain(entity);

        assertThat(report.getCommentId()).isEqualTo(entity.getCommentId());
        assertThat(report.getClaim().details().isEmpty()).isTrue();
        assertThat(mapper.toPayload(report.getSnapshot())).isEqualTo(entity.getSnapshot());
    }
}
