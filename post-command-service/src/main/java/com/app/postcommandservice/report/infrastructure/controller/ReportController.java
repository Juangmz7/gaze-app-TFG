package com.app.postcommandservice.report.infrastructure.controller;

import java.util.UUID;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.app.postcommandservice.shared.domain.model.pagination.PageLimit;
import com.app.postcommandservice.shared.infrastructure.pagination.KeysetCursorCodec;
import com.app.postcommandservice.shared.infrastructure.security.SecurityUtils;
import com.app.postcommandservice.report.application.commands.ListReportsQuery;
import com.app.postcommandservice.report.application.commands.ReportCommentCommand;
import com.app.postcommandservice.report.application.commands.ReportPostCommand;
import com.app.postcommandservice.report.application.dto.CommentReportItemResponse;
import com.app.postcommandservice.report.application.dto.PostReportItemResponse;
import com.app.postcommandservice.report.application.dto.ReportCreationResult;
import com.app.postcommandservice.report.application.dto.ReportPage;
import com.app.postcommandservice.report.application.dto.ReportReceiptResponse;
import com.app.postcommandservice.report.application.usecase.ListCommentReportsUseCase;
import com.app.postcommandservice.report.application.usecase.ListPostReportsUseCase;
import com.app.postcommandservice.report.application.usecase.ReportCommentUseCase;
import com.app.postcommandservice.report.application.usecase.ReportPostUseCase;
import com.app.postcommandservice.report.domain.model.valueobj.ReportStatus;

/**
 * Content reports (App Store guideline 1.2). The admin lists use the literal segment
 * {@code reports}, which Spring matches before the {@code /{postId}/...} patterns; the ADMIN
 * role is enforced in {@code SecurityConfig}.
 */
@RestController
@RequestMapping("/api/posts")
@RequiredArgsConstructor
public class ReportController {

    private final ReportPostUseCase reportPostUseCase;
    private final ReportCommentUseCase reportCommentUseCase;
    private final ListPostReportsUseCase listPostReportsUseCase;
    private final ListCommentReportsUseCase listCommentReportsUseCase;
    private final KeysetCursorCodec keysetCursorCodec;
    private final SecurityUtils securityUtils;

    @PostMapping("/{postId}/reports")
    public ResponseEntity<ReportReceiptResponse> reportPost(
            @PathVariable("postId") UUID postId,
            @Valid @RequestBody CreateReportRequest request) {
        var result = reportPostUseCase.report(new ReportPostCommand(
                postId,
                requireCurrentUserId(),
                request.reason(),
                request.details()
        ));
        return toResponse(result);
    }

    @PostMapping("/{postId}/comments/{commentId}/reports")
    public ResponseEntity<ReportReceiptResponse> reportComment(
            @PathVariable("postId") UUID postId,
            @PathVariable("commentId") UUID commentId,
            @Valid @RequestBody CreateReportRequest request) {
        var result = reportCommentUseCase.report(new ReportCommentCommand(
                postId,
                commentId,
                requireCurrentUserId(),
                request.reason(),
                request.details()
        ));
        return toResponse(result);
    }

    @GetMapping("/reports")
    public ResponseEntity<ReportPageResponse<PostReportItemResponse>> listPostReports(
            @RequestParam(name = "status", defaultValue = "OPEN") ReportStatus status,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) String limit) {
        return ResponseEntity.ok(toPageResponse(listPostReportsUseCase.list(toQuery(status, cursor, limit))));
    }

    @GetMapping("/comments/reports")
    public ResponseEntity<ReportPageResponse<CommentReportItemResponse>> listCommentReports(
            @RequestParam(name = "status", defaultValue = "OPEN") ReportStatus status,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) String limit) {
        return ResponseEntity.ok(toPageResponse(listCommentReportsUseCase.list(toQuery(status, cursor, limit))));
    }

    private UUID requireCurrentUserId() {
        var currentUserId = securityUtils.getUserId();
        if (currentUserId == null) {
            throw new AuthenticationCredentialsNotFoundException("JWT subject claim is missing or invalid");
        }
        return currentUserId;
    }

    private ListReportsQuery toQuery(ReportStatus status, String cursor, String limit) {
        var pageLimit = PageLimit.parse(limit);
        return new ListReportsQuery(status, keysetCursorCodec.decode(cursor), pageLimit);
    }

    private <T> ReportPageResponse<T> toPageResponse(ReportPage<T> page) {
        return new ReportPageResponse<>(page.items(), keysetCursorCodec.encode(page.nextPosition()));
    }

    private static ResponseEntity<ReportReceiptResponse> toResponse(ReportCreationResult result) {
        var status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(result.response());
    }
}
