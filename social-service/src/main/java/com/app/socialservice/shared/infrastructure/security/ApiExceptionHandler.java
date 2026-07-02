package com.app.socialservice.shared.infrastructure.security;

import com.app.socialservice.block.domain.exception.SelfBlockNotAllowedException;
import com.app.socialservice.block.domain.exception.SelfUnblockNotAllowedException;
import com.app.socialservice.block.domain.exception.UserNotFoundException;
import com.app.socialservice.follow.domain.exception.FollowBlockedException;
import com.app.socialservice.follow.domain.exception.SelfFollowNotAllowedException;
import com.app.socialservice.follow.domain.exception.SelfUnfollowNotAllowedException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleUserNotFoundException(
            UserNotFoundException exception,
            HttpServletRequest request) {

        var response = new ApiErrorResponse(
                Instant.now(),
                HttpStatus.NOT_FOUND.value(),
                HttpStatus.NOT_FOUND.getReasonPhrase(),
                exception.getMessage(),
                request.getRequestURI()
        );

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
    }

    @ExceptionHandler(com.app.socialservice.follow.domain.exception.UserNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleFollowUserNotFoundException(
            com.app.socialservice.follow.domain.exception.UserNotFoundException exception,
            HttpServletRequest request) {

        var response = new ApiErrorResponse(
                Instant.now(),
                HttpStatus.NOT_FOUND.value(),
                HttpStatus.NOT_FOUND.getReasonPhrase(),
                exception.getMessage(),
                request.getRequestURI()
        );

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
    }

    @ExceptionHandler(com.app.socialservice.user.domain.exception.UserNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleDomainUserNotFoundException(
            com.app.socialservice.user.domain.exception.UserNotFoundException exception,
            HttpServletRequest request) {

        var response = new ApiErrorResponse(
                Instant.now(),
                HttpStatus.NOT_FOUND.value(),
                HttpStatus.NOT_FOUND.getReasonPhrase(),
                exception.getMessage(),
                request.getRequestURI()
        );

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
    }

    @ExceptionHandler(SelfBlockNotAllowedException.class)
    public ResponseEntity<ApiErrorResponse> handleSelfBlockNotAllowedException(
            SelfBlockNotAllowedException exception,
            HttpServletRequest request) {

        var response = new ApiErrorResponse(
                Instant.now(),
                HttpStatus.BAD_REQUEST.value(),
                HttpStatus.BAD_REQUEST.getReasonPhrase(),
                exception.getMessage(),
                request.getRequestURI()
        );

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    @ExceptionHandler(SelfUnblockNotAllowedException.class)
    public ResponseEntity<ApiErrorResponse> handleSelfUnblockNotAllowedException(
            SelfUnblockNotAllowedException exception,
            HttpServletRequest request) {

        var response = new ApiErrorResponse(
                Instant.now(),
                HttpStatus.BAD_REQUEST.value(),
                HttpStatus.BAD_REQUEST.getReasonPhrase(),
                exception.getMessage(),
                request.getRequestURI()
        );

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    @ExceptionHandler(SelfFollowNotAllowedException.class)
    public ResponseEntity<ApiErrorResponse> handleSelfFollowNotAllowedException(
            SelfFollowNotAllowedException exception,
            HttpServletRequest request) {

        var response = new ApiErrorResponse(
                Instant.now(),
                HttpStatus.BAD_REQUEST.value(),
                HttpStatus.BAD_REQUEST.getReasonPhrase(),
                exception.getMessage(),
                request.getRequestURI()
        );

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    @ExceptionHandler(SelfUnfollowNotAllowedException.class)
    public ResponseEntity<ApiErrorResponse> handleSelfUnfollowNotAllowedException(
            SelfUnfollowNotAllowedException exception,
            HttpServletRequest request) {

        var response = new ApiErrorResponse(
                Instant.now(),
                HttpStatus.BAD_REQUEST.value(),
                HttpStatus.BAD_REQUEST.getReasonPhrase(),
                exception.getMessage(),
                request.getRequestURI()
        );

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    @ExceptionHandler(FollowBlockedException.class)
    public ResponseEntity<ApiErrorResponse> handleFollowBlockedException(
            FollowBlockedException exception,
            HttpServletRequest request) {

        var response = new ApiErrorResponse(
                Instant.now(),
                HttpStatus.FORBIDDEN.value(),
                HttpStatus.FORBIDDEN.getReasonPhrase(),
                exception.getMessage(),
                request.getRequestURI()
        );

        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> handleIllegalArgumentException(
            IllegalArgumentException exception,
            HttpServletRequest request) {

        var response = new ApiErrorResponse(
                Instant.now(),
                HttpStatus.BAD_REQUEST.value(),
                HttpStatus.BAD_REQUEST.getReasonPhrase(),
                exception.getMessage(),
                request.getRequestURI()
        );

        return ResponseEntity.badRequest().body(response);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleException(
            Exception exception,
            HttpServletRequest request) {

        var response = new ApiErrorResponse(
                Instant.now(),
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase(),
                "An unexpected error occurred",
                request.getRequestURI()
        );

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }
}
