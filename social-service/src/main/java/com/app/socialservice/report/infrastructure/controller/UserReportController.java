package com.app.socialservice.report.infrastructure.controller;

import java.util.UUID;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.app.socialservice.report.application.commands.ReportUserCommand;
import com.app.socialservice.report.application.dto.UserReportListResponse;
import com.app.socialservice.report.application.dto.UserReportReceiptResponse;
import com.app.socialservice.report.application.service.UserReportService;
import com.app.socialservice.report.domain.enums.ReportStatus;
import com.app.socialservice.report.infrastructure.request.ReportUserRequest;
import com.app.socialservice.shared.application.media.PictureUrlResponseSigner;
import com.app.socialservice.shared.infrastructure.security.SecurityUtils;

/**
 * The GET list requires the realm role ADMIN, enforced in SecurityConfig.
 */
@RestController
@RequestMapping("/api/social/reports/users")
@RequiredArgsConstructor
public class UserReportController {

    private final UserReportService userReportService;
    private final PictureUrlResponseSigner pictureUrlResponseSigner;
    private final SecurityUtils securityUtils;

    @PostMapping
    public ResponseEntity<UserReportReceiptResponse> reportUser(@Valid @RequestBody ReportUserRequest request) {
        var reporterUserId = requireAuthenticatedUserId();

        var result = userReportService.reportUser(new ReportUserCommand(
                reporterUserId,
                request.reportedUserId(),
                request.reason(),
                request.details()
        ));
        var status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(result.receipt());
    }

    /**
     * cursor and limit are taken as text: a malformed value answers invalid_cursor / invalid_limit
     * (validated by the use case), not a generic binding error.
     */
    @GetMapping
    public ResponseEntity<UserReportListResponse> listUserReports(
            @RequestParam(defaultValue = "OPEN") ReportStatus status,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) String limit) {

        var page = userReportService.listUserReports(status, cursor, limit);
        return ResponseEntity.ok(new UserReportListResponse(
                pictureUrlResponseSigner.signAll(page.items()),
                page.nextCursor()
        ));
    }

    private UUID requireAuthenticatedUserId() {
        var userId = securityUtils.getUserId();
        if (userId == null) {
            throw new AuthenticationCredentialsNotFoundException("User authentication failed");
        }
        return userId;
    }
}
