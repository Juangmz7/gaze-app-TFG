package com.app.postcommandservice.post.infrastructure.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.app.postcommandservice.comment.application.commands.CreateCommentCommand;
import com.app.postcommandservice.comment.application.commands.DeleteCommentCommand;
import com.app.postcommandservice.comment.application.commands.UpdateCommentCommand;
import com.app.postcommandservice.comment.application.dto.CommentResponse;
import com.app.postcommandservice.comment.application.usecase.CreateCommentUseCase;
import com.app.postcommandservice.comment.application.usecase.DeleteCommentUseCase;
import com.app.postcommandservice.comment.application.usecase.UpdateCommentUseCase;
import com.app.postcommandservice.commentlike.application.usecase.DispatchValidateCommentLikeCommandUseCase;
import com.app.postcommandservice.commentlike.application.usecase.DispatchValidateCommentUnlikeCommandUseCase;
import com.app.postcommandservice.collab.application.commands.OpenCollabForExistingPostCommand;
import com.app.postcommandservice.collab.application.dto.OpenCollabAndCreatePostResponse;
import com.app.postcommandservice.collab.application.usecase.OpenCollabForExistingPostUseCase;
import com.app.postcommandservice.post.application.commands.CheckPostCollabLinkStatusCommand;
import com.app.postcommandservice.like.application.usecase.DispatchValidatePostLikeCommandUseCase;
import com.app.postcommandservice.like.application.usecase.DispatchValidatePostUnlikeCommandUseCase;
import com.app.postcommandservice.post.application.commands.CreatePostCommand;
import com.app.postcommandservice.post.application.commands.DeletePostCommand;
import com.app.postcommandservice.post.application.commands.LinkExistingPostToCollabCommand;
import com.app.postcommandservice.post.application.commands.UpdatePostCommand;
import com.app.postcommandservice.post.application.dto.PostCollabLinkStatusResponse;
import com.app.postcommandservice.post.application.dto.PostResponse;
import com.app.postcommandservice.post.application.usecase.CheckPostCollabLinkStatusUseCase;
import com.app.postcommandservice.post.application.usecase.CreatePostUseCase;
import com.app.postcommandservice.post.application.usecase.DeletePostUseCase;
import com.app.postcommandservice.post.application.usecase.LinkExistingPostToCollabUseCase;
import com.app.postcommandservice.post.application.usecase.UpdatePostUseCase;
import com.app.postcommandservice.post.domain.model.valueobj.PostType;
import com.app.postcommandservice.share.application.commands.CreatePostShareCommand;
import com.app.postcommandservice.share.application.commands.DeletePostShareCommand;
import com.app.postcommandservice.share.application.usecase.CreatePostShareUseCase;
import com.app.postcommandservice.share.application.usecase.DeletePostShareUseCase;
import com.app.postcommandservice.shared.infrastructure.security.SecurityUtils;
import com.app.postcommandservice.view.application.usecase.DispatchProcessPostViewCommandUseCase;

@RestController
@RequestMapping("/api/posts")
@RequiredArgsConstructor
public class PostController {

    private final CreatePostUseCase createPostUseCase;
    private final CheckPostCollabLinkStatusUseCase checkPostCollabLinkStatusUseCase;
    private final UpdatePostUseCase updatePostUseCase;
    private final DeletePostUseCase deletePostUseCase;
    private final LinkExistingPostToCollabUseCase linkExistingPostToCollabUseCase;
    private final OpenCollabForExistingPostUseCase openCollabForExistingPostUseCase;
    private final CreateCommentUseCase createCommentUseCase;
    private final DeleteCommentUseCase deleteCommentUseCase;
    private final UpdateCommentUseCase updateCommentUseCase;
    private final DispatchValidateCommentLikeCommandUseCase dispatchValidateCommentLikeCommandUseCase;
    private final DispatchValidateCommentUnlikeCommandUseCase dispatchValidateCommentUnlikeCommandUseCase;
    private final DispatchValidatePostLikeCommandUseCase dispatchValidatePostLikeCommandUseCase;
    private final DispatchValidatePostUnlikeCommandUseCase dispatchValidatePostUnlikeCommandUseCase;
    private final DispatchProcessPostViewCommandUseCase dispatchProcessPostViewCommandUseCase;
    private final CreatePostShareUseCase createPostShareUseCase;
    private final DeletePostShareUseCase deletePostShareUseCase;
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
                null,
                PostType.BASIC,
                request.description(),
                request.taggedUsers(),
                request.postTags(),
                request.title(),
                request.media().stream().map(PostMediaRequest::toDomain).toList()
        );

        return ResponseEntity.ok(createPostUseCase.createPost(command));
    }

    @GetMapping("/{postId}/collab-status")
    public ResponseEntity<PostCollabLinkStatusResponse> checkPostCollabLinkStatus(
            @PathVariable("postId") java.util.UUID postId) {
        var currentUserId = securityUtils.getUserId();
        if (currentUserId == null) {
            throw new AuthenticationCredentialsNotFoundException("JWT subject claim is missing or invalid");
        }

        var result = checkPostCollabLinkStatusUseCase.check(new CheckPostCollabLinkStatusCommand(postId, currentUserId));

        return ResponseEntity.ok(new PostCollabLinkStatusResponse(
                result.linked(),
                result.collab()
        ));
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
                request.postTags(),
                request.title(),
                request.media() == null ? null : request.media().stream().map(PostMediaRequest::toDomain).toList()
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

    @PutMapping("/{postId}/collabs/{collabId}/link")
    public ResponseEntity<PostResponse> linkPostToCollab(
            @PathVariable("postId") java.util.UUID postId,
            @PathVariable("collabId") java.util.UUID collabId) {
        var currentUserId = securityUtils.getUserId();
        if (currentUserId == null) {
            throw new AuthenticationCredentialsNotFoundException("JWT subject claim is missing or invalid");
        }

        return ResponseEntity.ok(linkExistingPostToCollabUseCase.link(
                new LinkExistingPostToCollabCommand(postId, collabId, currentUserId)
        ));
    }

    @PostMapping("/{postId}/collabs")
    public ResponseEntity<OpenCollabAndCreatePostResponse> openCollabForExistingPost(
            @PathVariable("postId") java.util.UUID postId,
            @Valid @RequestBody OpenCollabForExistingPostRequest request) {
        var currentUserId = securityUtils.getUserId();
        if (currentUserId == null) {
            throw new AuthenticationCredentialsNotFoundException("JWT subject claim is missing or invalid");
        }

        return ResponseEntity.ok(openCollabForExistingPostUseCase.open(
                new OpenCollabForExistingPostCommand(
                        postId,
                        request.correlationId(),
                        currentUserId,
                        request.title()
                )
        ));
    }

    @PostMapping("/{postId}/like")
    public ResponseEntity<Void> likePost(
            @PathVariable("postId") java.util.UUID postId,
            @Valid @RequestBody PostLikeRequest request) {
        var currentUserId = securityUtils.getUserId();
        if (currentUserId == null) {
            throw new AuthenticationCredentialsNotFoundException("JWT subject claim is missing or invalid");
        }

        dispatchValidatePostLikeCommandUseCase.dispatch(
                postId,
                currentUserId,
                request.context().toSource(),
                request.context().feedPosition()
        );
        return ResponseEntity.accepted().build();
    }

    @DeleteMapping("/{postId}/like")
    public ResponseEntity<Void> unlikePost(
            @PathVariable("postId") java.util.UUID postId,
            @Valid @RequestBody PostLikeRequest request) {
        var currentUserId = securityUtils.getUserId();
        if (currentUserId == null) {
            throw new AuthenticationCredentialsNotFoundException("JWT subject claim is missing or invalid");
        }

        dispatchValidatePostUnlikeCommandUseCase.dispatch(
                postId,
                currentUserId,
                request.context().toSource(),
                request.context().feedPosition()
        );
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/{postId}/share")
    public ResponseEntity<Void> sharePost(@PathVariable("postId") java.util.UUID postId) {
        var currentUserId = securityUtils.getUserId();
        if (currentUserId == null) {
            throw new AuthenticationCredentialsNotFoundException("JWT subject claim is missing or invalid");
        }

        createPostShareUseCase.share(new CreatePostShareCommand(postId, currentUserId));
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{postId}/share")
    public ResponseEntity<Void> deletePostShare(@PathVariable("postId") java.util.UUID postId) {
        var currentUserId = securityUtils.getUserId();
        if (currentUserId == null) {
            throw new AuthenticationCredentialsNotFoundException("JWT subject claim is missing or invalid");
        }

        deletePostShareUseCase.delete(new DeletePostShareCommand(postId, currentUserId));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{postId}/comments")
    public ResponseEntity<CommentResponse> createComment(
            @PathVariable("postId") java.util.UUID postId,
            @Valid @RequestBody CreateCommentRequest request) {
        var currentUserId = securityUtils.getUserId();
        if (currentUserId == null) {
            throw new AuthenticationCredentialsNotFoundException("JWT subject claim is missing or invalid");
        }
        var command = new CreateCommentCommand(
                request.correlationId(),
                postId,
                currentUserId,
                request.content(),
                request.replyTo()
        );
        return ResponseEntity.ok(createCommentUseCase.createComment(command));
    }

    @PostMapping("/{postId}/comments/{commentId}/like")
    public ResponseEntity<Void> likeComment(
            @PathVariable("postId") java.util.UUID postId,
            @PathVariable("commentId") java.util.UUID commentId,
            @Valid @RequestBody CommentLikeRequest request) {
        var currentUserId = securityUtils.getUserId();
        if (currentUserId == null) {
            throw new AuthenticationCredentialsNotFoundException("JWT subject claim is missing or invalid");
        }

        dispatchValidateCommentLikeCommandUseCase.dispatch(
                postId,
                commentId,
                currentUserId,
                request.context().toSource(),
                request.context().feedPosition()
        );
        return ResponseEntity.accepted().build();
    }

    @DeleteMapping("/{postId}/comments/{commentId}/like")
    public ResponseEntity<Void> unlikeComment(
            @PathVariable("postId") java.util.UUID postId,
            @PathVariable("commentId") java.util.UUID commentId,
            @Valid @RequestBody CommentLikeRequest request) {
        var currentUserId = securityUtils.getUserId();
        if (currentUserId == null) {
            throw new AuthenticationCredentialsNotFoundException("JWT subject claim is missing or invalid");
        }

        dispatchValidateCommentUnlikeCommandUseCase.dispatch(
                postId,
                commentId,
                currentUserId,
                request.context().toSource(),
                request.context().feedPosition()
        );
        return ResponseEntity.accepted().build();
    }

    @DeleteMapping("/{postId}/comments/{commentId}")
    public ResponseEntity<Void> deleteComment(
            @PathVariable("postId") java.util.UUID postId,
            @PathVariable("commentId") java.util.UUID commentId) {
        var currentUserId = securityUtils.getUserId();
        if (currentUserId == null) {
            throw new AuthenticationCredentialsNotFoundException("JWT subject claim is missing or invalid");
        }
        
        deleteCommentUseCase.deleteComment(new DeleteCommentCommand(postId, commentId, currentUserId));
        return ResponseEntity.noContent().build();
    }
  
    @RequestMapping(path = "/{postId}/comments/{commentId}", method = {RequestMethod.PUT, RequestMethod.PATCH})
    public ResponseEntity<CommentResponse> updateComment(
            @PathVariable("postId") java.util.UUID postId,
            @PathVariable("commentId") java.util.UUID commentId,
            @Valid @RequestBody UpdateCommentRequest request) {
        var currentUserId = securityUtils.getUserId();
        if (currentUserId == null) {
            throw new AuthenticationCredentialsNotFoundException("JWT subject claim is missing or invalid");
        }
        var command = new UpdateCommentCommand(postId, commentId, currentUserId, request.content());
        return ResponseEntity.ok(updateCommentUseCase.updateComment(command));
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
