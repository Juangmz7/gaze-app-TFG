package com.app.postcommandservice.shared.infrastructure.security;

import java.time.Instant;
import java.util.Objects;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.validation.BindException;
import org.springframework.validation.BindingResult;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

import com.app.postcommandservice.comment.domain.exception.CommentBlockedException;
import com.app.postcommandservice.comment.domain.exception.CommentNotActiveException;
import com.app.postcommandservice.comment.domain.exception.CommentNotFoundException;
import com.app.postcommandservice.comment.domain.exception.CommentOwnershipException;
import com.app.postcommandservice.collab.domain.exception.CollabJoinRequestAccessDeniedException;
import com.app.postcommandservice.collab.domain.exception.CollabMemberNotFoundException;
import com.app.postcommandservice.collab.domain.exception.CollabJoinRequestBlockedException;
import com.app.postcommandservice.collab.domain.exception.CollabAdminAccessDeniedException;
import com.app.postcommandservice.collab.domain.exception.CollabNotFoundException;
import com.app.postcommandservice.collab.domain.exception.CollabNotOpenException;
import com.app.postcommandservice.collab.domain.exception.CollabAccessDeniedException;
import com.app.postcommandservice.post.domain.exception.TaggedUserBlockedException;
import com.app.postcommandservice.post.domain.exception.TaggedUserNotFoundException;
import com.app.postcommandservice.post.domain.exception.PostNotActiveException;
import com.app.postcommandservice.post.domain.exception.PostNotFoundException;
import com.app.postcommandservice.post.domain.exception.PostOwnershipException;
import com.app.postcommandservice.share.domain.exception.PostShareBlockedException;
import com.app.postcommandservice.shared.domain.exception.DomainException;

@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler({
            IllegalArgumentException.class,
            HttpRequestMethodNotSupportedException.class
    })
    public ResponseEntity<ApiErrorResponse> handleBadRequestException(
            RuntimeException exception,
            HttpServletRequest request) {

        return buildErrorResponse(HttpStatus.BAD_REQUEST, ApiErrorCode.BAD_REQUEST, exception.getMessage(), request);
    }


    @ExceptionHandler(AuthenticationCredentialsNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleAuthenticationCredentialsNotFoundException(
            AuthenticationCredentialsNotFoundException exception,
            HttpServletRequest request) {

        return buildErrorResponse(HttpStatus.UNAUTHORIZED, ApiErrorCode.INVALID_JWT, exception.getMessage(), request);
    }

    @ExceptionHandler(TaggedUserNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleTaggedUserNotFoundException(
            TaggedUserNotFoundException exception,
            HttpServletRequest request) {

        return buildErrorResponse(HttpStatus.NOT_FOUND, ApiErrorCode.NOT_FOUND, exception.getMessage(), request);
    }

    @ExceptionHandler(PostNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handlePostNotFoundException(
            PostNotFoundException exception,
            HttpServletRequest request) {

        return buildErrorResponse(HttpStatus.NOT_FOUND, ApiErrorCode.NOT_FOUND, exception.getMessage(), request);
    }

    @ExceptionHandler(CommentNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleCommentNotFoundException(
            CommentNotFoundException exception,
            HttpServletRequest request) {

        return buildErrorResponse(HttpStatus.NOT_FOUND, ApiErrorCode.NOT_FOUND, exception.getMessage(), request);
    }

    @ExceptionHandler(CollabNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleCollabNotFoundException(
            CollabNotFoundException exception,
            HttpServletRequest request) {

        return buildErrorResponse(HttpStatus.NOT_FOUND, ApiErrorCode.NOT_FOUND, exception.getMessage(), request);
    }

    @ExceptionHandler(CommentOwnershipException.class)
    public ResponseEntity<ApiErrorResponse> handleCommentOwnershipException(
            CommentOwnershipException exception,
            HttpServletRequest request) {

        return buildErrorResponse(HttpStatus.FORBIDDEN, ApiErrorCode.FORBIDDEN, exception.getMessage(), request);
    }

    @ExceptionHandler(CollabJoinRequestAccessDeniedException.class)
    public ResponseEntity<ApiErrorResponse> handleCollabJoinRequestAccessDeniedException(
            CollabJoinRequestAccessDeniedException exception,
            HttpServletRequest request) {

        return buildErrorResponse(HttpStatus.FORBIDDEN, ApiErrorCode.FORBIDDEN, exception.getMessage(), request);
    }
  
    @ExceptionHandler(CollabAccessDeniedException.class)
    public ResponseEntity<ApiErrorResponse> handleCollabAccessDeniedException(
            CollabAccessDeniedException exception,
            HttpServletRequest request) {

        return buildErrorResponse(HttpStatus.FORBIDDEN, ApiErrorCode.FORBIDDEN, exception.getMessage(), request);
    }

    @ExceptionHandler(TaggedUserBlockedException.class)
    public ResponseEntity<ApiErrorResponse> handleTaggedUserBlockedException(
            TaggedUserBlockedException exception,
            HttpServletRequest request) {

        return buildErrorResponse(HttpStatus.FORBIDDEN, ApiErrorCode.BLOCKED, exception.getMessage(), request);
    }

    @ExceptionHandler(CommentBlockedException.class)
    public ResponseEntity<ApiErrorResponse> handleCommentBlockedException(
            CommentBlockedException exception,
            HttpServletRequest request) {

        return buildErrorResponse(HttpStatus.BAD_REQUEST, ApiErrorCode.BLOCKED, exception.getMessage(), request);
    }

    @ExceptionHandler(CollabJoinRequestBlockedException.class)
    public ResponseEntity<ApiErrorResponse> handleCollabJoinRequestBlockedException(
            CollabJoinRequestBlockedException exception,
            HttpServletRequest request) {

        return buildErrorResponse(HttpStatus.BAD_REQUEST, ApiErrorCode.BLOCKED, exception.getMessage(), request);
    }
    @ExceptionHandler(PostShareBlockedException.class)
    public ResponseEntity<ApiErrorResponse> handlePostShareBlockedException(
            PostShareBlockedException exception,
            HttpServletRequest request) {

        return buildErrorResponse(HttpStatus.BAD_REQUEST, ApiErrorCode.BLOCKED, exception.getMessage(), request);
    }

    @ExceptionHandler(CommentNotActiveException.class)
    public ResponseEntity<ApiErrorResponse> handleCommentNotActiveException(
            CommentNotActiveException exception,
            HttpServletRequest request) {

        return buildErrorResponse(HttpStatus.BAD_REQUEST, ApiErrorCode.BAD_REQUEST, exception.getMessage(), request);
    }

    @ExceptionHandler(CollabMemberNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleCollabMemberNotFoundException(
            CollabMemberNotFoundException exception,
            HttpServletRequest request) {

        return buildErrorResponse(HttpStatus.NOT_FOUND, ApiErrorCode.NOT_FOUND, exception.getMessage(), request);
    }

    @ExceptionHandler(PostOwnershipException.class)
    public ResponseEntity<ApiErrorResponse> handlePostOwnershipException(
            PostOwnershipException exception,
            HttpServletRequest request) {

        return buildErrorResponse(HttpStatus.FORBIDDEN, ApiErrorCode.FORBIDDEN, exception.getMessage(), request);
    }

    @ExceptionHandler(CollabAdminAccessDeniedException.class)
    public ResponseEntity<ApiErrorResponse> handleCollabAdminAccessDeniedException(
            CollabAdminAccessDeniedException exception,
            HttpServletRequest request) {

        return buildErrorResponse(HttpStatus.FORBIDDEN, ApiErrorCode.FORBIDDEN, exception.getMessage(), request);
    }

    @ExceptionHandler(PostNotActiveException.class)
    public ResponseEntity<ApiErrorResponse> handlePostNotActiveException(
            PostNotActiveException exception,
            HttpServletRequest request) {

        return buildErrorResponse(HttpStatus.BAD_REQUEST, ApiErrorCode.BAD_REQUEST, exception.getMessage(), request);
    }

    @ExceptionHandler(CollabNotOpenException.class)
    public ResponseEntity<ApiErrorResponse> handleCollabNotOpenException(
            CollabNotOpenException exception,
            HttpServletRequest request) {

        return buildErrorResponse(HttpStatus.BAD_REQUEST, ApiErrorCode.BAD_REQUEST, exception.getMessage(), request);
    }

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ApiErrorResponse> handleDomainException(
            DomainException exception,
            HttpServletRequest request) {

        return buildErrorResponse(HttpStatus.BAD_REQUEST, ApiErrorCode.BAD_REQUEST, exception.getMessage(), request);
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
