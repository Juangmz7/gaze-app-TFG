package com.app.postcommandservice.report.application.usecase;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import com.app.postcommandservice.report.application.commands.ListReportsQuery;
import com.app.postcommandservice.report.application.mapper.ReportResponseMapper;
import com.app.postcommandservice.report.application.repository.CommentReportRepository;
import com.app.postcommandservice.report.domain.model.CommentReport;
import com.app.postcommandservice.report.domain.model.CommentReportSnapshot;
import com.app.postcommandservice.report.domain.model.ReportClaim;
import com.app.postcommandservice.report.domain.model.valueobj.ReportDetails;
import com.app.postcommandservice.report.domain.model.valueobj.ReportReason;
import com.app.postcommandservice.report.domain.model.valueobj.ReportStatus;
import com.app.postcommandservice.shared.domain.model.pagination.KeysetPosition;
import com.app.postcommandservice.shared.domain.model.pagination.PageLimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ListCommentReportsUseCaseTest {

    @Mock
    private CommentReportRepository commentReportRepository;

    @Spy
    private ReportResponseMapper reportResponseMapper = new ReportResponseMapper();

    @InjectMocks
    private ListCommentReportsUseCase listCommentReportsUseCase;

    @Test
    void shouldMapItemsWithCommentTargetAndNextPosition() {
        var rows = reports(2);
        when(commentReportRepository.findPage(ReportStatus.OPEN, null, 2)).thenReturn(rows);

        var page = listCommentReportsUseCase.list(new ListReportsQuery(ReportStatus.OPEN, null, new PageLimit(1)));

        assertThat(page.items()).singleElement().satisfies(item -> {
            assertThat(item.target().commentId()).isEqualTo(rows.get(0).getCommentId());
            assertThat(item.target().postId()).isEqualTo(rows.get(0).getPostId());
            assertThat(item.details()).isEqualTo("insults");
            assertThat(item.snapshot().content()).isEqualTo("rude");
        });
        assertThat(page.nextPosition())
                .isEqualTo(new KeysetPosition(rows.get(0).getCreatedAt(), rows.get(0).getId()));
    }

    @Test
    void shouldReturnNoNextPositionWhenRowsFitThePage() {
        when(commentReportRepository.findPage(ReportStatus.OPEN, null, 21)).thenReturn(reports(1));

        var page = listCommentReportsUseCase.list(new ListReportsQuery(ReportStatus.OPEN, null, PageLimit.parse(null)));

        assertThat(page.items()).hasSize(1);
        assertThat(page.nextPosition()).isNull();
    }

    private static List<CommentReport> reports(int count) {
        var base = Instant.parse("2026-11-10T12:00:00Z");
        return IntStream.range(0, count)
                .mapToObj(index -> {
                    var postId = UUID.randomUUID();
                    return new CommentReport(
                            UUID.randomUUID(),
                            postId,
                            UUID.randomUUID(),
                            new ReportClaim(UUID.randomUUID(), ReportReason.OTHER, new ReportDetails("insults")),
                            new CommentReportSnapshot("rude", UUID.randomUUID(), postId),
                            ReportStatus.OPEN,
                            base.minusSeconds(index));
                })
                .toList();
    }
}
