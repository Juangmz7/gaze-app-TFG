package com.app.postcommandservice.report.application.usecase;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.postcommandservice.comment.application.repository.CommentRepository;
import com.app.postcommandservice.comment.domain.model.valueobj.CommentStatus;
import com.app.postcommandservice.post.application.repository.PostRepository;
import com.app.postcommandservice.post.domain.model.valueobj.PostStatus;
import com.app.postcommandservice.report.application.commands.ReportCommentCommand;
import com.app.postcommandservice.report.application.dto.ReportCreationResult;
import com.app.postcommandservice.report.application.mapper.ReportResponseMapper;
import com.app.postcommandservice.report.application.repository.CommentReportRepository;
import com.app.postcommandservice.report.domain.exception.ReportedCommentNotFoundException;
import com.app.postcommandservice.report.domain.exception.ReportedPostNotFoundException;
import com.app.postcommandservice.report.domain.model.CommentReport;
import com.app.postcommandservice.report.domain.model.CommentReportSnapshot;
import com.app.postcommandservice.report.domain.model.ReportClaim;
import com.app.postcommandservice.report.domain.model.valueobj.ReportDetails;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReportCommentUseCase {

    private final PostRepository postRepository;
    private final CommentRepository commentRepository;
    private final CommentReportRepository commentReportRepository;
    private final ReportResponseMapper reportResponseMapper;

    @Transactional
    public ReportCreationResult report(ReportCommentCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        Objects.requireNonNull(command.postId(), "postId must not be null");
        Objects.requireNonNull(command.commentId(), "commentId must not be null");

        var claim = new ReportClaim(
                command.reporterUserId(),
                command.reason(),
                new ReportDetails(command.details())
        );

        postRepository.findById(command.postId())
                .filter(post -> post.getStatus() == PostStatus.ACCEPTED)
                .orElseThrow(() -> new ReportedPostNotFoundException(command.postId()));

        var comment = commentRepository.findByIdAndPostId(command.commentId(), command.postId())
                .filter(found -> found.getStatus() == CommentStatus.ACTIVE)
                .orElseThrow(() -> new ReportedCommentNotFoundException(command.commentId()));

        var report = CommentReport.open(
                UUID.randomUUID(),
                command.postId(),
                command.commentId(),
                claim,
                new CommentReportSnapshot(
                        comment.getContent().value(),
                        comment.getUserId().value(),
                        comment.getPostId().value()
                ),
                Instant.now().truncatedTo(ChronoUnit.MICROS)
        );

        var existing = commentReportRepository.findOpenByReporterAndComment(
                claim.reporterUserId(), command.commentId());
        if (existing.isPresent()) {
            return new ReportCreationResult(reportResponseMapper.toReceipt(existing.get()), false);
        }

        if (commentReportRepository.insertIfNoOpenReport(report)) {
            log.info("Comment report {} opened on comment {}", report.getId(), command.commentId());
            return new ReportCreationResult(reportResponseMapper.toReceipt(report), true);
        }

        // A concurrent request of the same reporter won the unique OPEN index
        var concurrent = commentReportRepository.findOpenByReporterAndComment(
                        claim.reporterUserId(), command.commentId())
                .orElseThrow(() -> new IllegalStateException(
                        "OPEN comment report vanished after a unique conflict on comment " + command.commentId()));
        return new ReportCreationResult(reportResponseMapper.toReceipt(concurrent), false);
    }
}
