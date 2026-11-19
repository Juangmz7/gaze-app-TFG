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
import com.app.postcommandservice.report.application.repository.PostReportRepository;
import com.app.postcommandservice.report.domain.model.PostReport;
import com.app.postcommandservice.report.domain.model.PostReportSnapshot;
import com.app.postcommandservice.report.domain.model.ReportClaim;
import com.app.postcommandservice.report.domain.model.valueobj.ReportReason;
import com.app.postcommandservice.report.domain.model.valueobj.ReportStatus;
import com.app.postcommandservice.shared.domain.model.pagination.KeysetPosition;
import com.app.postcommandservice.shared.domain.model.pagination.PageLimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ListPostReportsUseCaseTest {

    @Mock
    private PostReportRepository postReportRepository;

    @Spy
    private ReportResponseMapper reportResponseMapper = new ReportResponseMapper();

    @InjectMocks
    private ListPostReportsUseCase listPostReportsUseCase;

    @Test
    void shouldReturnNextPositionOfLastItemWhenAnExtraRowExists() {
        var rows = reports(3);
        when(postReportRepository.findPage(ReportStatus.OPEN, null, 3)).thenReturn(rows);

        var page = listPostReportsUseCase.list(new ListReportsQuery(ReportStatus.OPEN, null, new PageLimit(2)));

        assertThat(page.items()).hasSize(2);
        assertThat(page.items().get(1).reportId()).isEqualTo(rows.get(1).getId());
        assertThat(page.nextPosition())
                .isEqualTo(new KeysetPosition(rows.get(1).getCreatedAt(), rows.get(1).getId()));
    }

    @Test
    void shouldReturnNoNextPositionOnLastPage() {
        var after = new KeysetPosition(Instant.parse("2026-11-19T00:00:00Z"), UUID.randomUUID());
        var rows = reports(2);
        when(postReportRepository.findPage(ReportStatus.RESOLVED, after, 3)).thenReturn(rows);

        var page = listPostReportsUseCase.list(new ListReportsQuery(ReportStatus.RESOLVED, after, new PageLimit(2)));

        assertThat(page.items()).hasSize(2);
        assertThat(page.items().get(0).target().postId()).isEqualTo(rows.get(0).getPostId());
        assertThat(page.nextPosition()).isNull();
    }

    private static List<PostReport> reports(int count) {
        var base = Instant.parse("2026-11-10T12:00:00Z");
        return IntStream.range(0, count)
                .mapToObj(index -> new PostReport(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        new ReportClaim(UUID.randomUUID(), ReportReason.NUDITY, null),
                        new PostReportSnapshot("caption", List.of(), UUID.randomUUID()),
                        ReportStatus.OPEN,
                        base.minusSeconds(index)))
                .toList();
    }
}
