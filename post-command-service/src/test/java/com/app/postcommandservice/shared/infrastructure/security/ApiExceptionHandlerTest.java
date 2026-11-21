package com.app.postcommandservice.shared.infrastructure.security;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import com.app.postcommandservice.post.domain.exception.MediaUploadWindowExpiredException;
import com.app.postcommandservice.post.domain.exception.PostNotPendingException;
import com.app.postcommandservice.post.domain.model.valueobj.PostStatus;
import com.app.postcommandservice.post.infrastructure.entity.PostEntity;

import com.app.postcommandservice.report.domain.exception.CannotReportOwnContentException;
import com.app.postcommandservice.report.domain.exception.ReportedCommentNotFoundException;
import com.app.postcommandservice.report.domain.exception.ReportedPostNotFoundException;
import com.app.postcommandservice.shared.domain.exception.InvalidCursorException;
import com.app.postcommandservice.shared.domain.exception.InvalidPageLimitException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class ApiExceptionHandlerTest {

    private final ApiExceptionHandler apiExceptionHandler = new ApiExceptionHandler();

    @Test
    void shouldMapObjectOptimisticLockingFailureExceptionToHttpConflict() {
        var request = new MockHttpServletRequest();
        request.setRequestURI("/api/posts");
        var exception = new ObjectOptimisticLockingFailureException(PostEntity.class, UUID.randomUUID());

        var response = apiExceptionHandler.handleOptimisticLockingFailureException(exception, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getErrorCode()).isEqualTo(ApiErrorCode.CONFLICT);
        assertThat(response.getBody().getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(response.getBody().getPath()).isEqualTo("/api/posts");
    }

    @Test
    void shouldMapPostNotPendingExceptionToHttpConflict() {
        var request = new MockHttpServletRequest();
        request.setRequestURI("/api/posts/" + UUID.randomUUID() + "/media/confirm");
        var exception = new PostNotPendingException(UUID.randomUUID(), PostStatus.ACCEPTED, "confirm media upload");

        var response = apiExceptionHandler.handlePostNotPendingException(exception, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getErrorCode()).isEqualTo(ApiErrorCode.CONFLICT);
    }

    @Test
    void shouldMapMediaUploadWindowExpiredExceptionToHttpConflict() {
        var request = new MockHttpServletRequest();
        request.setRequestURI("/api/posts/" + UUID.randomUUID() + "/media/confirm");
        var exception = new MediaUploadWindowExpiredException(Instant.now().minusSeconds(100), Instant.now());

        var response = apiExceptionHandler.handleMediaUploadWindowExpiredException(exception, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getErrorCode()).isEqualTo(ApiErrorCode.CONFLICT);
    }

    @Test
    void shouldMapReportErrorsToSnakeCaseContractCodes() {
        var request = new MockHttpServletRequest();
        request.setRequestURI("/api/posts/reports");

        assertThat(apiExceptionHandler.handleReportedPostNotFoundException(
                new ReportedPostNotFoundException(UUID.randomUUID()), request).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(apiExceptionHandler.handleReportedCommentNotFoundException(
                new ReportedCommentNotFoundException(UUID.randomUUID()), request).getBody().getErrorCode())
                .isEqualTo(ApiErrorCode.COMMENT_NOT_FOUND);
        assertThat(apiExceptionHandler.handleCannotReportOwnContentException(
                new CannotReportOwnContentException(), request).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(apiExceptionHandler.handleInvalidCursorException(
                new InvalidCursorException("bad"), request).getBody().getErrorCode())
                .isEqualTo(ApiErrorCode.INVALID_CURSOR);
        assertThat(apiExceptionHandler.handleInvalidPageLimitException(
                new InvalidPageLimitException("bad"), request).getBody().getErrorCode())
                .isEqualTo(ApiErrorCode.INVALID_LIMIT);

        var json = JsonMapper.builder().build();
        assertThat(json.writeValueAsString(ApiErrorCode.POST_NOT_FOUND)).isEqualTo("\"post_not_found\"");
        assertThat(json.writeValueAsString(ApiErrorCode.CANNOT_REPORT_OWN_CONTENT))
                .isEqualTo("\"cannot_report_own_content\"");
        assertThat(json.writeValueAsString(ApiErrorCode.INVALID_CURSOR)).isEqualTo("\"invalid_cursor\"");
        assertThat(json.writeValueAsString(ApiErrorCode.INVALID_LIMIT)).isEqualTo("\"invalid_limit\"");
        assertThat(json.writeValueAsString(ApiErrorCode.NOT_FOUND)).isEqualTo("\"NOT_FOUND\"");
    }

    @Test
    void shouldMapUnreadableBodyToBadRequestWithoutLeakingParserDetails() {
        var request = new MockHttpServletRequest();
        request.setRequestURI("/api/posts/" + UUID.randomUUID() + "/reports");
        var exception = new HttpMessageNotReadableException(
                "Cannot deserialize value of type ReportReason", new MockHttpInputMessage(new byte[0]));

        var response = apiExceptionHandler.handleHttpMessageNotReadableException(exception, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getErrorCode()).isEqualTo(ApiErrorCode.VALIDATION_ERROR);
        assertThat(response.getBody().getMessage()).doesNotContain("ReportReason");
    }
}
