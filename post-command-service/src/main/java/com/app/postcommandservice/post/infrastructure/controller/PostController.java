package com.app.postcommandservice.post.infrastructure.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.app.postcommandservice.comment.application.commands.CreateCommentCommand;
import com.app.postcommandservice.comment.application.dto.CommentResponse;
import com.app.postcommandservice.comment.application.usecase.CreateCommentUseCase;
import com.app.postcommandservice.like.application.usecase.DispatchValidatePostLikeCommandUseCase;
import com.app.postcommandservice.like.application.usecase.DispatchValidatePostUnlikeCommandUseCase;
import com.app.postcommandservice.post.application.commands.CreatePostCommand;
import com.app.postcommandservice.post.application.commands.DeletePostCommand;
import com.app.postcommandservice.post.application.commands.UpdatePostCommand;
import com.app.postcommandservice.post.application.dto.PostResponse;
import com.app.postcommandservice.post.application.usecase.CreatePostUseCase;
import com.app.postcommandservice.post.application.usecase.DeletePostUseCase;
import com.app.postcommandservice.post.application.usecase.UpdatePostUseCase;
import com.app.postcommandservice.shared.infrastructure.security.SecurityUtils;
import com.app.postcommandservice.view.application.usecase.DispatchProcessPostViewCommandUseCase;

@RestController
@RequestMapping("/api/posts")
@RequiredArgsConstructor
public class PostController {

    private final CreatePostUseCase createPostUseCase;
    private final UpdatePostUseCase updatePostUseCase;
    private final DeletePostUseCase deletePostUseCase;
    private final CreateCommentUseCase createCommentUseCase;
    private final DispatchValidatePostLikeCommandUseCase dispatchValidatePostLikeCommandUseCase;
    private final DispatchValidatePostUnlikeCommandUseCase dispatchValidatePostUnlikeCommandUseCase;
    private final DispatchProcessPostViewCommandUseCase dispatchProcessPostViewCommandUseCase;
    private final SecurityUtils securityUtils;

    @PostMapping
    public ResponseEntity<PostResponse> createPost(@Valid @RequestBody CreatePostRequest request) {
        var currentUserId = securityUtils.getUserId();
        if (currentUserId == null) {
            throw new AuthenticationCredentialsNotFoundException("JWT subject claim is missing or invalid");
        }

        var command = new CreatePostCommand(
                request.correlationId(),
                currentUserId,
                request.description(),
                request.taggedUsers(),
                request.postTags()
        );

        return ResponseEntity.ok(createPostUseCase.createPost(command));
    }

    @PutMapping
    public ResponseEntity<PostResponse> updatePost(@Valid @RequestBody UpdatePostRequest request) {
        var currentUserId = securityUtils.getUserId();
        if (currentUserId == null) {
            throw new AuthenticationCredentialsNotFoundException("JWT subject claim is missing or invalid");
        }

        var command = new UpdatePostCommand(
                request.postId(),
                currentUserId,
                request.description(),
                request.taggedUsers(),
                request.postTags()
        );

        return ResponseEntity.ok(updatePostUseCase.updatePost(command));
    }

    @DeleteMapping("/{postId}")
    public ResponseEntity<Void> deletePost(@PathVariable("postId") java.util.UUID postId) {
        var currentUserId = securityUtils.getUserId();
        if (currentUserId == null) {
            throw new AuthenticationCredentialsNotFoundException("JWT subject claim is missing or invalid");
        }

        deletePostUseCase.deletePost(new DeletePostCommand(postId, currentUserId));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{postId}/like")
    public ResponseEntity<Void> likePost(@PathVariable("postId") java.util.UUID postId) {
        var currentUserId = securityUtils.getUserId();
        if (currentUserId == null) {
            throw new AuthenticationCredentialsNotFoundException("JWT subject claim is missing or invalid");
        }

        dispatchValidatePostLikeCommandUseCase.dispatch(postId, currentUserId);
        return ResponseEntity.accepted().build();
    }

    @DeleteMapping("/{postId}/like")
    public ResponseEntity<Void> unlikePost(@PathVariable("postId") java.util.UUID postId) {
        var currentUserId = securityUtils.getUserId();
        if (currentUserId == null) {
            throw new AuthenticationCredentialsNotFoundException("JWT subject claim is missing or invalid");
        }

        dispatchValidatePostUnlikeCommandUseCase.dispatch(postId, currentUserId);
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/{postId}/comments")
    public ResponseEntity<CommentResponse> createComment(
            @PathVariable("postId") java.util.UUID postId,
            @Valid @RequestBody CreateCommentRequest request) {
        var currentUserId = securityUtils.getUserId();
        if (currentUserId == null) {
            throw new AuthenticationCredentialsNotFoundException("JWT subject claim is missing or invalid");
        }
        var command = new CreateCommentCommand(postId, currentUserId, request.content(), request.replyTo());
        return ResponseEntity.ok(createCommentUseCase.createComment(command));
    }
  
    @PostMapping("/{postId}/views")
    public ResponseEntity<Void> reportPostView(
            @PathVariable("postId") java.util.UUID postId,
            @Valid @RequestBody ReportPostViewRequest request) {
        var currentUserId = securityUtils.getUserId();
        if (currentUserId == null) {
            throw new AuthenticationCredentialsNotFoundException("JWT subject claim is missing or invalid");
        }
        if (!postId.equals(request.postId())) {
            throw new IllegalArgumentException("Request postId must match the path postId");
        }

        dispatchProcessPostViewCommandUseCase.dispatch(
                request.viewId(),
                request.postId(),
                currentUserId,
                request.context().toSource(),
                request.context().feedPosition(),
                request.playbackMetrics().durationMs(),
                request.playbackMetrics().timeWatchedMs(),
                request.playbackMetrics().completionPercent(),
                request.playbackMetrics().toExitReason()
        );
        return ResponseEntity.accepted().build();
    }
}
