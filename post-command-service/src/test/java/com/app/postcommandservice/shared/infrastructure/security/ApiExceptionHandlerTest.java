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
}
