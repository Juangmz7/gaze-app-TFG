package com.app.postcommandservice.report.application.usecase;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import com.app.postcommandservice.comment.application.repository.CommentRepository;
import com.app.postcommandservice.comment.domain.model.Comment;
import com.app.postcommandservice.comment.domain.model.valueobj.CommentContent;
import com.app.postcommandservice.comment.domain.model.valueobj.CommentId;
import com.app.postcommandservice.comment.domain.model.valueobj.CommentStatus;
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
import com.app.postcommandservice.report.application.commands.ReportCommentCommand;
import com.app.postcommandservice.report.application.mapper.ReportResponseMapper;
import com.app.postcommandservice.report.application.repository.CommentReportRepository;
import com.app.postcommandservice.report.domain.exception.CannotReportOwnContentException;
import com.app.postcommandservice.report.domain.exception.ReportedCommentNotFoundException;
import com.app.postcommandservice.report.domain.exception.ReportedPostNotFoundException;
import com.app.postcommandservice.report.domain.model.CommentReport;
import com.app.postcommandservice.report.domain.model.CommentReportSnapshot;
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
class ReportCommentUseCaseTest {

    private static final UUID POST_ID = UUID.randomUUID();
    private static final UUID COMMENT_ID = UUID.randomUUID();
    private static final UUID POST_AUTHOR_ID = UUID.randomUUID();
    private static final UUID COMMENT_AUTHOR_ID = UUID.randomUUID();
    private static final UUID REPORTER_ID = UUID.randomUUID();

    @Mock
    private PostRepository postRepository;

    @Mock
    private CommentRepository commentRepository;

    @Mock
    private CommentReportRepository commentReportRepository;

    @Spy
    private ReportResponseMapper reportResponseMapper = new ReportResponseMapper();

    @InjectMocks
    private ReportCommentUseCase reportCommentUseCase;

    @Test
    void shouldOpenReportWithCommentSnapshot() {
        when(postRepository.findById(POST_ID)).thenReturn(Optional.of(post(PostStatus.ACCEPTED)));
        when(commentRepository.findByIdAndPostId(COMMENT_ID, POST_ID))
                .thenReturn(Optional.of(comment(CommentStatus.ACTIVE)));
        when(commentReportRepository.findOpenByReporterAndComment(REPORTER_ID, COMMENT_ID))
                .thenReturn(Optional.empty());
        when(commentReportRepository.insertIfNoOpenReport(any())).thenReturn(true);

        var result = reportCommentUseCase.report(command(REPORTER_ID));

        var captor = ArgumentCaptor.forClass(CommentReport.class);
        verify(commentReportRepository).insertIfNoOpenReport(captor.capture());
        assertThat(result.created()).isTrue();
        assertThat(captor.getValue().getSnapshot())
                .isEqualTo(new CommentReportSnapshot("rude words", COMMENT_AUTHOR_ID, POST_ID));
    }

    @Test
    void shouldReturnExistingOpenReportForDuplicate() {
        var existing = new CommentReport(UUID.randomUUID(), POST_ID, COMMENT_ID,
                new ReportClaim(REPORTER_ID, ReportReason.HARASSMENT, null),
                new CommentReportSnapshot("rude words", COMMENT_AUTHOR_ID, POST_ID),
                ReportStatus.OPEN, Instant.parse("2026-11-01T10:00:00Z"));
        when(postRepository.findById(POST_ID)).thenReturn(Optional.of(post(PostStatus.ACCEPTED)));
        when(commentRepository.findByIdAndPostId(COMMENT_ID, POST_ID))
                .thenReturn(Optional.of(comment(CommentStatus.ACTIVE)));
        when(commentReportRepository.findOpenByReporterAndComment(REPORTER_ID, COMMENT_ID))
                .thenReturn(Optional.of(existing));

        var result = reportCommentUseCase.report(command(REPORTER_ID));

        assertThat(result.created()).isFalse();
        assertThat(result.response().reportId()).isEqualTo(existing.getId());
        verify(commentReportRepository, never()).insertIfNoOpenReport(any());
    }

    @Test
    void shouldRejectReportingOwnComment() {
        when(postRepository.findById(POST_ID)).thenReturn(Optional.of(post(PostStatus.ACCEPTED)));
        when(commentRepository.findByIdAndPostId(COMMENT_ID, POST_ID))
                .thenReturn(Optional.of(comment(CommentStatus.ACTIVE)));

        assertThatThrownBy(() -> reportCommentUseCase.report(command(COMMENT_AUTHOR_ID)))
                .isInstanceOf(CannotReportOwnContentException.class);
        verifyNoInteractions(commentReportRepository);
    }

    @Test
    void shouldAnswerCommentNotFoundForDeletedComment() {
        when(postRepository.findById(POST_ID)).thenReturn(Optional.of(post(PostStatus.ACCEPTED)));
        when(commentRepository.findByIdAndPostId(COMMENT_ID, POST_ID))
                .thenReturn(Optional.of(comment(CommentStatus.DELETED)));

        assertThatThrownBy(() -> reportCommentUseCase.report(command(REPORTER_ID)))
                .isInstanceOf(ReportedCommentNotFoundException.class);
    }

    @Test
    void shouldAnswerPostNotFoundWhenThePostIsGone() {
        when(postRepository.findById(POST_ID)).thenReturn(Optional.of(post(PostStatus.DELETED)));

        assertThatThrownBy(() -> reportCommentUseCase.report(command(REPORTER_ID)))
                .isInstanceOf(ReportedPostNotFoundException.class);
        verifyNoInteractions(commentRepository, commentReportRepository);
    }

    private static ReportCommentCommand command(UUID reporterId) {
        return new ReportCommentCommand(POST_ID, COMMENT_ID, reporterId, ReportReason.HARASSMENT, null);
    }

    private static Comment comment(CommentStatus status) {
        return new Comment(new CommentId(COMMENT_ID), new PostId(POST_ID), new UserId(COMMENT_AUTHOR_ID),
                new CommentContent("rude words"), null, status, Instant.now(), Instant.now(), null);
    }

    private static Post post(PostStatus status) {
        var media = status == PostStatus.DELETED
                ? List.<PostMedia>of()
                : List.of(new PostMedia(UUID.randomUUID(), POST_ID, "https://acc.blob/media/first", null,
                        MediaType.IMAGE, null, Set.of(), 1));
        return new Post(new PostId(POST_ID), new UserId(POST_AUTHOR_ID), null,
                new PostInfo(new PostDescription("caption"), new PostTags(Set.of("tag")), PostType.BASIC),
                media, status, Instant.now(), Instant.now());
    }
}
