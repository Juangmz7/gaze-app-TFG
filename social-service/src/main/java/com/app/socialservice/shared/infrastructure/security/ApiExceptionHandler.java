package com.app.socialservice.shared.infrastructure.security;

import java.time.Instant;
import java.util.Objects;

import com.app.socialservice.block.domain.exception.SelfBlockNotAllowedException;
import com.app.socialservice.block.domain.exception.SelfUnblockNotAllowedException;
import com.app.socialservice.block.domain.exception.UserNotFoundException;
import com.app.socialservice.follow.domain.exception.FollowBlockedException;
import com.app.socialservice.follow.domain.exception.SelfFollowNotAllowedException;
import com.app.socialservice.follow.domain.exception.SelfUnfollowNotAllowedException;
import com.app.socialservice.user.domain.exception.InvalidEmailException;
import com.app.socialservice.user.domain.exception.InvalidProfilePictureUrlException;
import com.app.socialservice.user.domain.exception.InvalidUserIdException;
import com.app.socialservice.user.domain.exception.InvalidUsernameException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

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


    @ExceptionHandler({
            InvalidEmailException.class,
            InvalidProfilePictureUrlException.class,
            InvalidUserIdException.class,
            InvalidUsernameException.class
    })
    public ResponseEntity<ApiErrorResponse> handleUserValidationException(
            RuntimeException exception,
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

    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            HandlerMethodValidationException.class,
            BindException.class,
            ConstraintViolationException.class
    })
    public ResponseEntity<ApiErrorResponse> handleRequestValidationException(
            Exception exception,
            HttpServletRequest request) {

        var response = new ApiErrorResponse(
                Instant.now(),
                HttpStatus.BAD_REQUEST.value(),
                HttpStatus.BAD_REQUEST.getReasonPhrase(),
                resolveValidationMessage(exception),
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

    private String resolveValidationMessage(Exception exception) {
        if (exception instanceof MethodArgumentNotValidException methodArgumentNotValidException) {
            return resolveBindingResultMessage(methodArgumentNotValidException.getBindingResult());
        }
        if (exception instanceof BindException bindException) {
            return resolveBindingResultMessage(bindException.getBindingResult());
        }
        if (exception instanceof ConstraintViolationException constraintViolationException) {
            return constraintViolationException.getConstraintViolations().stream()
                    .map(violation -> violation.getMessage())
                    .filter(Objects::nonNull)
                    .filter(message -> !message.isBlank())
                    .findFirst()
                    .orElse("Request validation failed");
        }
        return "Request validation failed";
    }

    private String resolveBindingResultMessage(BindingResult bindingResult) {
        return bindingResult.getAllErrors().stream()
                .map(error -> error.getDefaultMessage())
                .filter(Objects::nonNull)
                .filter(message -> !message.isBlank())
                .findFirst()
                .orElse("Request validation failed");
    }
}
