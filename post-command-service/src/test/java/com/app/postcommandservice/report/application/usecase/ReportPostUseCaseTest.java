package com.app.postcommandservice.report.application.usecase;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;

import com.app.postcommandservice.post.application.repository.PostRepository;
import com.app.postcommandservice.post.domain.model.Post;
import com.app.postcommandservice.post.domain.model.PostInfo;
import com.app.postcommandservice.post.domain.model.PostMedia;
import com.app.postcommandservice.post.domain.model.valueobj.MediaType;
import com.app.postcommandservice.post.domain.model.valueobj.PostDescription;
import com.app.postcommandservice.post.domain.model.valueobj.PostId;
import com.app.postcommandservice.post.domain.model.valueobj.PostStatus;
import com.app.postcommandservice.post.domain.model.valueobj.PostTags;
import com.app.postcommandservice.post.domain.model.valueobj.PostType;
import com.app.postcommandservice.report.application.commands.ReportPostCommand;
import com.app.postcommandservice.report.application.mapper.ReportResponseMapper;
import com.app.postcommandservice.report.application.repository.PostReportRepository;
import com.app.postcommandservice.report.domain.exception.CannotReportOwnContentException;
import com.app.postcommandservice.report.domain.exception.InvalidReportDetailsException;
import com.app.postcommandservice.report.domain.exception.ReportedPostNotFoundException;
import com.app.postcommandservice.report.domain.model.PostReport;
import com.app.postcommandservice.report.domain.model.PostReportSnapshot;
import com.app.postcommandservice.report.domain.model.ReportClaim;
import com.app.postcommandservice.report.domain.model.valueobj.ReportReason;
import com.app.postcommandservice.report.domain.model.valueobj.ReportStatus;
import com.app.postcommandservice.shared.domain.model.user.valueobj.UserId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReportPostUseCaseTest {

    private static final UUID POST_ID = UUID.randomUUID();
    private static final UUID AUTHOR_ID = UUID.randomUUID();
    private static final UUID REPORTER_ID = UUID.randomUUID();

    @Mock
    private PostRepository postRepository;

    @Mock
    private PostReportRepository postReportRepository;

    @Spy
    private ReportResponseMapper reportResponseMapper = new ReportResponseMapper();

    @InjectMocks
    private ReportPostUseCase reportPostUseCase;

    @Test
    void shouldOpenReportWithSnapshotOfAcceptedPost() {
        when(postRepository.findById(POST_ID)).thenReturn(Optional.of(post(PostStatus.ACCEPTED)));
        when(postReportRepository.findOpenByReporterAndPost(REPORTER_ID, POST_ID)).thenReturn(Optional.empty());
        when(postReportRepository.insertIfNoOpenReport(any())).thenReturn(true);

        var result = reportPostUseCase.report(command(ReportReason.SPAM, null));

        var captor = ArgumentCaptor.forClass(PostReport.class);
        verify(postReportRepository).insertIfNoOpenReport(captor.capture());
        var saved = captor.getValue();
        assertThat(result.created()).isTrue();
        assertThat(result.response().reportId()).isEqualTo(saved.getId());
        assertThat(result.response().status()).isEqualTo(ReportStatus.OPEN);
        assertThat(saved.getSnapshot().description()).isEqualTo("caption");
        assertThat(saved.getSnapshot().mediaUrls())
                .containsExactly("https://acc.blob/media/first", "https://acc.blob/media/second");
        assertThat(saved.getSnapshot().authorUserId()).isEqualTo(AUTHOR_ID);
    }

    @Test
    void shouldReturnExistingOpenReportForDuplicate() {
        var existing = existingReport();
        when(postRepository.findById(POST_ID)).thenReturn(Optional.of(post(PostStatus.ACCEPTED)));
        when(postReportRepository.findOpenByReporterAndPost(REPORTER_ID, POST_ID)).thenReturn(Optional.of(existing));

        var result = reportPostUseCase.report(command(ReportReason.SPAM, null));

        assertThat(result.created()).isFalse();
        assertThat(result.response().reportId()).isEqualTo(existing.getId());
        assertThat(result.response().createdAt()).isEqualTo(existing.getCreatedAt());
        verify(postReportRepository, never()).insertIfNoOpenReport(any());
    }

    @Test
    void shouldReturnConcurrentWinnerWhenInsertHitsOpenIndex() {
        var winner = existingReport();
        when(postRepository.findById(POST_ID)).thenReturn(Optional.of(post(PostStatus.ACCEPTED)));
        when(postReportRepository.findOpenByReporterAndPost(REPORTER_ID, POST_ID))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner));
        when(postReportRepository.insertIfNoOpenReport(any())).thenReturn(false);

        var result = reportPostUseCase.report(command(ReportReason.SPAM, null));

        assertThat(result.created()).isFalse();
        assertThat(result.response().reportId()).isEqualTo(winner.getId());
    }

    @Test
    void shouldRejectReportingOwnPost() {
        when(postRepository.findById(POST_ID)).thenReturn(Optional.of(post(PostStatus.ACCEPTED)));

        assertThatThrownBy(() -> reportPostUseCase.report(
                new ReportPostCommand(POST_ID, AUTHOR_ID, ReportReason.SPAM, null)))
                .isInstanceOf(CannotReportOwnContentException.class);
        verifyNoInteractions(postReportRepository);
    }

    @Test
    void shouldRejectOtherWithoutDetailsBeforeLoadingThePost() {
        assertThatThrownBy(() -> reportPostUseCase.report(command(ReportReason.OTHER, "  ")))
                .isInstanceOf(InvalidReportDetailsException.class);
        verifyNoInteractions(postRepository, postReportRepository);
    }

    @Test
    void shouldRejectDetailsOverOneThousandCharacters() {
        assertThatThrownBy(() -> reportPostUseCase.report(command(ReportReason.SPAM, "x".repeat(1001))))
                .isInstanceOf(InvalidReportDetailsException.class);
    }

    @Test
    void shouldAnswerNotFoundForMissingOrNotAcceptedPost() {
        when(postRepository.findById(POST_ID))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(post(PostStatus.DELETED)))
                .thenReturn(Optional.of(post(PostStatus.PENDING)));

        for (int attempt = 0; attempt < 3; attempt++) {
            assertThatThrownBy(() -> reportPostUseCase.report(command(ReportReason.SPAM, null)))
                    .isInstanceOf(ReportedPostNotFoundException.class);
        }
        verifyNoInteractions(postReportRepository);
    }

    private static ReportPostCommand command(ReportReason reason, String details) {
        return new ReportPostCommand(POST_ID, REPORTER_ID, reason, details);
    }

    private static PostReport existingReport() {
        return new PostReport(
                UUID.randomUUID(),
                POST_ID,
                new ReportClaim(REPORTER_ID, ReportReason.SPAM, null),
                new PostReportSnapshot("caption", List.of(), AUTHOR_ID),
                ReportStatus.OPEN,
                Instant.parse("2026-11-01T10:00:00Z")
        );
    }

    private static Post post(PostStatus status) {
        var media = status == PostStatus.DELETED
                ? List.<PostMedia>of()
                : List.of(
                        new PostMedia(UUID.randomUUID(), POST_ID, "https://acc.blob/media/second", null,
                                MediaType.IMAGE, null, Set.of(), 2),
                        new PostMedia(UUID.randomUUID(), POST_ID, "https://acc.blob/media/first", null,
                                MediaType.IMAGE, null, Set.of(), 1));
        return new Post(
                new PostId(POST_ID),
                new UserId(AUTHOR_ID),
                null,
                new PostInfo(new PostDescription("caption"), new PostTags(Set.of("tag")), PostType.BASIC),
                media,
                status,
                Instant.now(),
                Instant.now()
        );
    }
}
