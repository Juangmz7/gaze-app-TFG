package com.app.postcommandservice.post.infrastructure.controller;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import com.app.postcommandservice.comment.application.dto.CommentResponse;
import com.app.postcommandservice.comment.application.usecase.CreateCommentUseCase;
import com.app.postcommandservice.comment.application.usecase.DeleteCommentUseCase;
import com.app.postcommandservice.commentlike.application.usecase.DispatchValidateCommentLikeCommandUseCase;
import com.app.postcommandservice.commentlike.domain.model.CommentLikeSource;
import com.app.postcommandservice.like.application.usecase.DispatchValidatePostLikeCommandUseCase;
import com.app.postcommandservice.like.application.usecase.DispatchValidatePostUnlikeCommandUseCase;
import com.app.postcommandservice.like.domain.model.PostLikeSource;
import com.app.postcommandservice.post.application.usecase.CreatePostUseCase;
import com.app.postcommandservice.post.application.usecase.DeletePostUseCase;
import com.app.postcommandservice.post.application.usecase.UpdatePostUseCase;
import com.app.postcommandservice.shared.infrastructure.security.SecurityUtils;
import com.app.postcommandservice.view.application.usecase.DispatchProcessPostViewCommandUseCase;
import com.app.postcommandservice.view.domain.model.PostViewExitReason;
import com.app.postcommandservice.view.domain.model.PostViewSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PostLikeControllerIT {

    @Mock
    private CreatePostUseCase createPostUseCase;

    @Mock
    private UpdatePostUseCase updatePostUseCase;

    @Mock
    private DeletePostUseCase deletePostUseCase;

    @Mock
    private CreateCommentUseCase createCommentUseCase;

    @Mock
    private DeleteCommentUseCase deleteCommentUseCase;

    @Mock
    private DispatchValidateCommentLikeCommandUseCase dispatchValidateCommentLikeCommandUseCase;

    @Mock
    private DispatchValidatePostLikeCommandUseCase dispatchValidatePostLikeCommandUseCase;

    @Mock
    private DispatchValidatePostUnlikeCommandUseCase dispatchValidatePostUnlikeCommandUseCase;

    @Mock
    private DispatchProcessPostViewCommandUseCase dispatchProcessPostViewCommandUseCase;

    @Mock
    private SecurityUtils securityUtils;

    @InjectMocks
    private PostController postController;

    @Test
    void shouldDispatchValidatePostLikeCommandAndReturnAccepted() {
        var postId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var request = new PostLikeRequest(new PostLikeContextRequest("search", 8));
        when(securityUtils.getUserId()).thenReturn(userId);

        var response = postController.likePost(postId, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        verify(dispatchValidatePostLikeCommandUseCase).dispatch(postId, userId, PostLikeSource.SEARCH, 8);
    }

    @Test
    void shouldDispatchValidatePostUnlikeCommandAndReturnAccepted() {
        var postId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var request = new PostLikeRequest(new PostLikeContextRequest("user_profile", 2));
        when(securityUtils.getUserId()).thenReturn(userId);

        var response = postController.unlikePost(postId, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        verify(dispatchValidatePostUnlikeCommandUseCase).dispatch(postId, userId, PostLikeSource.USER_PROFILE, 2);
    }

    @Test
    void shouldDispatchValidateCommentLikeCommandAndReturnAccepted() {
        var postId = UUID.randomUUID();
        var commentId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var request = new CommentLikeRequest(new CommentLikeContextRequest("home_feed", 6));
        when(securityUtils.getUserId()).thenReturn(userId);

        var response = postController.likeComment(postId, commentId, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        verify(dispatchValidateCommentLikeCommandUseCase)
                .dispatch(postId, commentId, userId, CommentLikeSource.HOME_FEED, 6);
    }

    @Test
    void shouldCreateCommentAndReturnOk() {
        var postId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var responseBody = new CommentResponse(UUID.randomUUID(), postId, userId, "hello", null, null, null);
        when(securityUtils.getUserId()).thenReturn(userId);
        when(createCommentUseCase.createComment(new com.app.postcommandservice.comment.application.commands.CreateCommentCommand(
                postId,
                userId,
                "hello",
                null
        ))).thenReturn(responseBody);

        var response = postController.createComment(postId, new CreateCommentRequest("hello", null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(responseBody);
    }

    @Test
    void shouldDeleteCommentAndReturnNoContent() {
        var postId = UUID.randomUUID();
        var commentId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        when(securityUtils.getUserId()).thenReturn(userId);

        var response = postController.deleteComment(postId, commentId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(deleteCommentUseCase).deleteComment(
                new com.app.postcommandservice.comment.application.commands.DeleteCommentCommand(postId, commentId, userId)
        );
    }
  
    void shouldDispatchProcessPostViewCommandAndReturnAccepted() {
        var postId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var request = new ReportPostViewRequest(
                UUID.randomUUID(),
                postId,
                new ReportPostViewRequest.ViewContextRequest("home_feed", 3),
                new ReportPostViewRequest.PlaybackMetricsRequest(1000, 750, 75, "scroll_next")
        );
        when(securityUtils.getUserId()).thenReturn(userId);

        var response = postController.reportPostView(postId, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        verify(dispatchProcessPostViewCommandUseCase).dispatch(
                request.viewId(),
                postId,
                userId,
                PostViewSource.HOME_FEED,
                3,
                1000,
                750,
                75,
                PostViewExitReason.SCROLL_NEXT
        );
    }
}
