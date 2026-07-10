package com.app.socialservice.shared.infrastructure.security;

import java.time.Instant;
import java.util.Objects;

import com.app.socialservice.block.domain.exception.SelfBlockNotAllowedException;
import com.app.socialservice.block.domain.exception.SelfUnblockNotAllowedException;
import com.app.socialservice.follow.domain.exception.FollowBlockedException;
import com.app.socialservice.follow.domain.exception.SelfFollowNotAllowedException;
import com.app.socialservice.follow.domain.exception.SelfUnfollowNotAllowedException;
import com.app.socialservice.shared.domain.exception.UserNotFoundException;
import com.app.socialservice.user.domain.exception.InvalidEmailException;
import com.app.socialservice.user.domain.exception.InvalidProfilePictureUrlException;
import com.app.socialservice.user.domain.exception.InvalidUserIdException;
import com.app.socialservice.user.domain.exception.InvalidUsernameException;
import com.app.socialservice.user.domain.exception.SelfProfileRequestNotAllowedException;
import com.app.socialservice.user.domain.exception.UserProfileBlockedException;
import com.app.socialservice.user.domain.exception.UserProfileNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.validation.BindException;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler({
            UserNotFoundException.class,
            UserProfileNotFoundException.class
    })
    public ResponseEntity<ApiErrorResponse> handleNotFoundException(
            RuntimeException exception,
            HttpServletRequest request) {

        return buildErrorResponse(HttpStatus.NOT_FOUND, exception.getMessage(), request);
    }

    @ExceptionHandler({
            SelfBlockNotAllowedException.class,
            SelfUnblockNotAllowedException.class,
            SelfFollowNotAllowedException.class,
            SelfUnfollowNotAllowedException.class,
            SelfProfileRequestNotAllowedException.class,
            InvalidEmailException.class,
            InvalidProfilePictureUrlException.class,
            InvalidUserIdException.class,
            InvalidUsernameException.class,
            IllegalArgumentException.class
    })
    public ResponseEntity<ApiErrorResponse> handleBadRequestException(
            RuntimeException exception,
            HttpServletRequest request) {

        return buildErrorResponse(HttpStatus.BAD_REQUEST, exception.getMessage(), request);
    }

    @ExceptionHandler({
            FollowBlockedException.class,
            UserProfileBlockedException.class
    })
    public ResponseEntity<ApiErrorResponse> handleForbiddenException(
            RuntimeException exception,
            HttpServletRequest request) {

        return buildErrorResponse(HttpStatus.FORBIDDEN, exception.getMessage(), request);
    }

    @ExceptionHandler(AuthenticationCredentialsNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleAuthenticationCredentialsNotFoundException(
            AuthenticationCredentialsNotFoundException exception,
            HttpServletRequest request) {

        return buildErrorResponse(HttpStatus.UNAUTHORIZED, exception.getMessage(), request);
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ApiErrorResponse> handleOptimisticLockingFailureException(
            ObjectOptimisticLockingFailureException exception,
            HttpServletRequest request) {

        return buildErrorResponse(
                HttpStatus.CONFLICT,
                "The resource was modified concurrently. Please retry the request.",
                request
        );
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

        return buildErrorResponse(HttpStatus.BAD_REQUEST, resolveValidationMessage(exception), request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleException(
            Exception exception,
            HttpServletRequest request) {
        log.error("Unexpected error on {}: {}", request.getRequestURI(), exception.getMessage(), exception);
        return buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred", request);
    }

    private ResponseEntity<ApiErrorResponse> buildErrorResponse(
            HttpStatus status,
            String message,
            HttpServletRequest request
    ) {
        var response = new ApiErrorResponse(
                Instant.now(),
                status.value(),
                status.getReasonPhrase(),
                message,
                request.getRequestURI()
        );
        return ResponseEntity.status(status).body(response);
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
