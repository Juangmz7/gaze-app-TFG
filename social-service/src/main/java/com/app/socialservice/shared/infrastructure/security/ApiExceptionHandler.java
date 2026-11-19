package com.app.socialservice.shared.infrastructure.security;

import java.time.Instant;
import java.util.Objects;

import com.app.socialservice.block.domain.exception.SelfBlockNotAllowedException;
import com.app.socialservice.block.domain.exception.SelfUnblockNotAllowedException;
import com.app.socialservice.follow.domain.exception.FollowBlockedException;
import com.app.socialservice.follow.domain.exception.SelfFollowNotAllowedException;
import com.app.socialservice.follow.domain.exception.SelfUnfollowNotAllowedException;
import com.app.socialservice.report.domain.exception.CannotReportSelfException;
import com.app.socialservice.report.domain.exception.InvalidReportDetailsException;
import com.app.socialservice.report.domain.exception.ReportedUserNotFoundException;
import com.app.socialservice.shared.domain.exception.UserNotFoundException;
import com.app.socialservice.shared.domain.exception.DomainException;
import com.app.socialservice.shared.domain.exception.InvalidCursorException;
import com.app.socialservice.shared.domain.exception.InvalidPageLimitException;
import com.app.socialservice.user.domain.exception.InvalidEmailException;
import com.app.socialservice.user.domain.exception.InvalidProfilePictureException;
import com.app.socialservice.user.domain.exception.InvalidProfilePictureSizeException;
import com.app.socialservice.user.domain.exception.InvalidProfilePictureUrlException;
import com.app.socialservice.user.domain.exception.InvalidUserIdException;
import com.app.socialservice.user.domain.exception.InvalidUsernameException;
import com.app.socialservice.user.domain.exception.InvalidUsernameLookupException;
import com.app.socialservice.user.domain.exception.ProfilePictureNotUploadedException;
import com.app.socialservice.user.domain.exception.SelfProfileRequestNotAllowedException;
import com.app.socialservice.user.domain.exception.UnsupportedProfilePictureContentTypeException;
import com.app.socialservice.user.domain.exception.UserBannedException;
import com.app.socialservice.user.domain.exception.UserProfileBlockedException;
import com.app.socialservice.user.domain.exception.UserProfileNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.validation.BindException;
import org.springframework.validation.BindingResult;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

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

        return buildErrorResponse(HttpStatus.NOT_FOUND, ApiErrorCode.NOT_FOUND, exception.getMessage(), request);
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
            IllegalArgumentException.class,
            HttpRequestMethodNotSupportedException.class
    })
    public ResponseEntity<ApiErrorResponse> handleBadRequestException(
            RuntimeException exception,
            HttpServletRequest request) {

        return buildErrorResponse(HttpStatus.BAD_REQUEST, ApiErrorCode.BAD_REQUEST, exception.getMessage(), request);
    }

    @ExceptionHandler({
            UnsupportedProfilePictureContentTypeException.class,
            InvalidProfilePictureSizeException.class,
            InvalidProfilePictureException.class,
            ProfilePictureNotUploadedException.class
    })
    public ResponseEntity<ApiErrorResponse> handleProfilePictureException(
            DomainException exception,
            HttpServletRequest request) {

        var errorCode = switch (exception) {
            case UnsupportedProfilePictureContentTypeException ignored -> ApiErrorCode.UNSUPPORTED_CONTENT_TYPE;
            case InvalidProfilePictureSizeException ignored -> ApiErrorCode.INVALID_SIZE;
            case ProfilePictureNotUploadedException ignored -> ApiErrorCode.PROFILE_PICTURE_NOT_UPLOADED;
            default -> ApiErrorCode.INVALID_PROFILE_PICTURE;
        };
        log.warn("Profile picture request rejected on {}: errorCode={}, reason={}",
                request.getRequestURI(), errorCode, exception.getMessage());
        return buildErrorResponse(HttpStatus.BAD_REQUEST, errorCode, exception.getMessage(), request);
    }

    @ExceptionHandler(InvalidUsernameLookupException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidUsernameLookupException(
            InvalidUsernameLookupException exception,
            HttpServletRequest request) {

        log.warn("Username lookup rejected on {}: {}", request.getRequestURI(), exception.getMessage());
        return buildErrorResponse(
                HttpStatus.BAD_REQUEST,
                ApiErrorCode.INVALID_USERNAME,
                exception.getMessage(),
                request
        );
    }

    @ExceptionHandler({
            InvalidCursorException.class,
            InvalidPageLimitException.class
    })
    public ResponseEntity<ApiErrorResponse> handlePaginationException(
            DomainException exception,
            HttpServletRequest request) {

        var errorCode = exception instanceof InvalidCursorException
                ? ApiErrorCode.INVALID_CURSOR
                : ApiErrorCode.INVALID_LIMIT;
        log.warn("Pagination request rejected on {}: errorCode={}, reason={}",
                request.getRequestURI(), errorCode, exception.getMessage());
        return buildErrorResponse(HttpStatus.BAD_REQUEST, errorCode, exception.getMessage(), request);
    }

    /**
     * A path or query value that does not convert to the declared type (e.g. a malformed UUID in
     * /follow/{userId}/followers): a client error, not a 500. The message names only the parameter.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiErrorResponse> handleMethodArgumentTypeMismatchException(
            MethodArgumentTypeMismatchException exception,
            HttpServletRequest request) {

        log.warn("Malformed request parameter on {}: name={}", request.getRequestURI(), exception.getName());
        return buildErrorResponse(
                HttpStatus.BAD_REQUEST,
                ApiErrorCode.BAD_REQUEST,
                String.format("%s is malformed", exception.getName()),
                request
        );
    }

    @ExceptionHandler(ReportedUserNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleReportedUserNotFoundException(
            ReportedUserNotFoundException exception,
            HttpServletRequest request) {

        return buildErrorResponse(HttpStatus.NOT_FOUND, ApiErrorCode.USER_NOT_FOUND, exception.getMessage(), request);
    }

    @ExceptionHandler(CannotReportSelfException.class)
    public ResponseEntity<ApiErrorResponse> handleCannotReportSelfException(
            CannotReportSelfException exception,
            HttpServletRequest request) {

        return buildErrorResponse(
                HttpStatus.BAD_REQUEST, ApiErrorCode.CANNOT_REPORT_SELF, exception.getMessage(), request);
    }

    @ExceptionHandler(InvalidReportDetailsException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidReportDetailsException(
            InvalidReportDetailsException exception,
            HttpServletRequest request) {

        return buildErrorResponse(
                HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_ERROR, exception.getMessage(), request);
    }

    /**
     * Malformed JSON, an unknown enum constant or a malformed UUID in a request body: a client
     * error, never a 500. The parser message is not echoed because it can leak internal types.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleHttpMessageNotReadableException(
            HttpMessageNotReadableException exception,
            HttpServletRequest request) {

        log.warn("Unreadable request body on {}: {}", request.getRequestURI(), exception.getClass().getSimpleName());
        return buildErrorResponse(
                HttpStatus.BAD_REQUEST,
                ApiErrorCode.VALIDATION_ERROR,
                "Request body is malformed or contains invalid values",
                request
        );
    }

    @ExceptionHandler({
            FollowBlockedException.class,
            UserProfileBlockedException.class
    })
    public ResponseEntity<ApiErrorResponse> handleForbiddenException(
            RuntimeException exception,
            HttpServletRequest request) {

        return buildErrorResponse(HttpStatus.FORBIDDEN, ApiErrorCode.BLOCKED, exception.getMessage(), request);
    }

    @ExceptionHandler(UserBannedException.class)
    public ResponseEntity<ApiErrorResponse> handleUserBannedException(
            UserBannedException exception,
            HttpServletRequest request) {

        return buildErrorResponse(HttpStatus.FORBIDDEN, ApiErrorCode.USER_BANNED, exception.getMessage(), request);
    }

    @ExceptionHandler(AuthenticationCredentialsNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleAuthenticationCredentialsNotFoundException(
            AuthenticationCredentialsNotFoundException exception,
            HttpServletRequest request) {

        return buildErrorResponse(HttpStatus.UNAUTHORIZED, ApiErrorCode.INVALID_JWT, exception.getMessage(), request);
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ApiErrorResponse> handleOptimisticLockingFailureException(
            ObjectOptimisticLockingFailureException exception,
            HttpServletRequest request) {

        return buildErrorResponse(
                HttpStatus.CONFLICT,
                ApiErrorCode.CONFLICT,
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

        return buildErrorResponse(
                HttpStatus.BAD_REQUEST,
                ApiErrorCode.VALIDATION_ERROR,
                resolveValidationMessage(exception),
                request
        );
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleException(
            Exception exception,
            HttpServletRequest request) {
        log.error("Unexpected error on {}: {}", request.getRequestURI(), exception.getMessage(), exception);
        return buildErrorResponse(
                HttpStatus.INTERNAL_SERVER_ERROR,
                ApiErrorCode.INTERNAL_ERROR,
                "An unexpected error occurred",
                request
        );
    }

    private ResponseEntity<ApiErrorResponse> buildErrorResponse(
            HttpStatus status,
            ApiErrorCode errorCode,
            String message,
            HttpServletRequest request
    ) {
        var response = new ApiErrorResponse(
                Instant.now(),
                status.value(),
                status.getReasonPhrase(),
                errorCode,
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
