package com.app.postcommandservice.post.infrastructure.controller;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import com.app.postcommandservice.like.application.usecase.DispatchValidatePostLikeCommandUseCase;
import com.app.postcommandservice.like.application.usecase.DispatchValidatePostUnlikeCommandUseCase;
import com.app.postcommandservice.post.application.usecase.CreatePostUseCase;
import com.app.postcommandservice.post.application.usecase.DeletePostUseCase;
import com.app.postcommandservice.post.application.usecase.UpdatePostUseCase;
import com.app.postcommandservice.shared.infrastructure.security.SecurityUtils;

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
    private DispatchValidatePostLikeCommandUseCase dispatchValidatePostLikeCommandUseCase;

    @Mock
    private DispatchValidatePostUnlikeCommandUseCase dispatchValidatePostUnlikeCommandUseCase;

    @Mock
    private SecurityUtils securityUtils;

    @InjectMocks
    private PostController postController;

    @Test
    void shouldDispatchValidatePostLikeCommandAndReturnAccepted() {
        var postId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        when(securityUtils.getUserId()).thenReturn(userId);

        var response = postController.likePost(postId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        verify(dispatchValidatePostLikeCommandUseCase).dispatch(postId, userId);
    }

    @Test
    void shouldDispatchValidatePostUnlikeCommandAndReturnAccepted() {
        var postId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        when(securityUtils.getUserId()).thenReturn(userId);

        var response = postController.unlikePost(postId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        verify(dispatchValidatePostUnlikeCommandUseCase).dispatch(postId, userId);
    }
}
