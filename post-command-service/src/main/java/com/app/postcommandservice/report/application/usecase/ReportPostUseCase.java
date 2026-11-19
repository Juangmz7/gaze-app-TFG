package com.app.postcommandservice.report.application.usecase;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.Objects;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.postcommandservice.post.application.repository.PostRepository;
import com.app.postcommandservice.post.domain.model.Post;
import com.app.postcommandservice.post.domain.model.PostMedia;
import com.app.postcommandservice.post.domain.model.valueobj.PostStatus;
import com.app.postcommandservice.report.application.commands.ReportPostCommand;
import com.app.postcommandservice.report.application.dto.ReportCreationResult;
import com.app.postcommandservice.report.application.mapper.ReportResponseMapper;
import com.app.postcommandservice.report.application.repository.PostReportRepository;
import com.app.postcommandservice.report.domain.exception.ReportedPostNotFoundException;
import com.app.postcommandservice.report.domain.model.PostReport;
import com.app.postcommandservice.report.domain.model.PostReportSnapshot;
import com.app.postcommandservice.report.domain.model.ReportClaim;
import com.app.postcommandservice.report.domain.model.valueobj.ReportDetails;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReportPostUseCase {

    private final PostRepository postRepository;
    private final PostReportRepository postReportRepository;
    private final ReportResponseMapper reportResponseMapper;

    @Transactional
    public ReportCreationResult report(ReportPostCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        Objects.requireNonNull(command.postId(), "postId must not be null");

        var claim = new ReportClaim(
                command.reporterUserId(),
                command.reason(),
                new ReportDetails(command.details())
        );

        var post = postRepository.findById(command.postId())
                .filter(found -> found.getStatus() == PostStatus.ACCEPTED)
                .orElseThrow(() -> new ReportedPostNotFoundException(command.postId()));

        var report = PostReport.open(
                UUID.randomUUID(),
                command.postId(),
                claim,
                toSnapshot(post),
                Instant.now().truncatedTo(ChronoUnit.MICROS)
        );

        var existing = postReportRepository.findOpenByReporterAndPost(claim.reporterUserId(), command.postId());
        if (existing.isPresent()) {
            return new ReportCreationResult(reportResponseMapper.toReceipt(existing.get()), false);
        }

        if (postReportRepository.insertIfNoOpenReport(report)) {
            log.info("Post report {} opened on post {}", report.getId(), command.postId());
            return new ReportCreationResult(reportResponseMapper.toReceipt(report), true);
        }

        // A concurrent request of the same reporter won the unique OPEN index
        var concurrent = postReportRepository.findOpenByReporterAndPost(claim.reporterUserId(), command.postId())
                .orElseThrow(() -> new IllegalStateException(
                        "OPEN post report vanished after a unique conflict on post " + command.postId()));
        return new ReportCreationResult(reportResponseMapper.toReceipt(concurrent), false);
    }

    private static PostReportSnapshot toSnapshot(Post post) {
        var mediaUrls = post.getMedia().stream()
                .sorted(Comparator.comparingInt(PostMedia::getOrder))
                .map(PostMedia::getUrl)
                .toList();
        return new PostReportSnapshot(post.getDescription().value(), mediaUrls, post.getUserId().value());
    }
}
